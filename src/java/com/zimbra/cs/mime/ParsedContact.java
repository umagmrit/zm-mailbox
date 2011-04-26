/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2007, 2008, 2009, 2010, 2011 Zimbra, Inc.
 * 
 * The contents of this file are subject to the Zimbra Public License
 * Version 1.3 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.mime;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.mail.MessagingException;
import javax.mail.Part;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.SharedInputStream;
import javax.mail.util.SharedByteArrayInputStream;

import org.json.JSONException;

import com.google.common.base.Strings;
import com.zimbra.common.mailbox.ContactConstants;
import com.zimbra.common.mime.ContentDisposition;
import com.zimbra.common.mime.MimeConstants;
import com.zimbra.common.mime.shim.JavaMailMimeBodyPart;
import com.zimbra.common.mime.shim.JavaMailMimeMultipart;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.ByteUtil;
import com.zimbra.common.util.CalculatorStream;
import com.zimbra.common.util.StringUtil;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.convert.ConversionException;
import com.zimbra.cs.index.LuceneFields;
import com.zimbra.cs.index.IndexDocument;
import com.zimbra.cs.index.analysis.FieldTokenStream;
import com.zimbra.cs.index.analysis.RFC822AddressTokenStream;
import com.zimbra.cs.localconfig.DebugConfig;
import com.zimbra.cs.mailbox.Contact;
import com.zimbra.cs.mailbox.MailServiceException;
import com.zimbra.cs.mailbox.Mailbox;
import com.zimbra.cs.mailbox.Contact.Attachment;
import com.zimbra.cs.object.ObjectHandlerException;
import com.zimbra.cs.util.JMSession;

public class ParsedContact {

    private Map<String, String> contactFields;
    private List<Attachment> contactAttachments;
    private InputStream sharedStream; // Used when parsing existing blobs.
    private MimeMessage mimeMessage; // Used when assembling a contact from attachments.
    private String digest;
    private long size;

    private List<IndexDocument> mZDocuments;

    /**
     * @param fields maps field names to either a <tt>String</tt> or <tt>String[]</tt> value.
     */
    public ParsedContact(Map<String, ? extends Object> fields) throws ServiceException {
        init(fields, null);
    }

    /**
     * @param fields maps field names to either a <tt>String</tt> or <tt>String[]</tt> value.
     */
    public ParsedContact(Map<String, ? extends Object> fields, byte[] content) throws ServiceException {
        InputStream in = null;
        if (content != null) {
            in = new SharedByteArrayInputStream(content);
        }
        init(fields, in);
    }

    /**
     * @param fields maps field names to either a <tt>String</tt> or <tt>String[]</tt> value.
     */
    public ParsedContact(Map<String, String> fields, InputStream in) throws ServiceException {
        init(fields, in);
    }

    /**
     * @param fields maps field names to either a <tt>String</tt> or <tt>String[]</tt> value.
     */
    public ParsedContact(Map<String, ? extends Object> fields, List<Attachment> attachments) throws ServiceException {
        init(fields, null);

        if (attachments != null && !attachments.isEmpty()) {
            try {
                contactAttachments = attachments;
                mimeMessage = generateMimeMessage(attachments);
                digest = ByteUtil.getSHA1Digest(Mime.getInputStream(mimeMessage), true);

                for (Attachment attach : contactAttachments) {
                    contactFields.remove(attach.getName());
                }
                if (contactFields.isEmpty()) {
                    throw ServiceException.INVALID_REQUEST("contact must have fields", null);
                }
                initializeSizeAndDigest(); // This didn't happen in init() because there was no stream.
            } catch (MessagingException me) {
                throw MailServiceException.MESSAGE_PARSE_ERROR(me);
            } catch (IOException ioe) {
                throw MailServiceException.MESSAGE_PARSE_ERROR(ioe);
            }
        }
    }

    public ParsedContact(Contact con) throws ServiceException {
        init(con.getAllFields(), con.getContentStream());
    }

    private void init(Map<String, ? extends Object> fields, InputStream in) throws ServiceException {
        if (fields == null) {
            throw ServiceException.INVALID_REQUEST("contact must have fields", null);
        }
        // Initialized shared stream.
        try {
            if (in instanceof SharedInputStream) {
                sharedStream = in;
            } else if (in != null) {
                byte[] content = ByteUtil.getContent(in, 1024);
                sharedStream = new SharedByteArrayInputStream(content);
            }
        } catch (IOException e) {
            throw MailServiceException.MESSAGE_PARSE_ERROR(e);
        }

        // Initialize fields.
        Map<String, String> map = new HashMap<String, String>();
        for (Map.Entry<String, ? extends Object> entry : fields.entrySet()) {
            String key = StringUtil.stripControlCharacters(entry.getKey());
            String value = null;
            if (entry.getValue() instanceof String[]) {
                // encode multi value attributes as JSONObject
                try {
                    value = Contact.encodeMultiValueAttr((String[]) entry.getValue());
                } catch (JSONException e) {
                    ZimbraLog.index.warn("Error encoding multi valued attribute " + key, e);
                }
            } else if (entry.getValue() instanceof String) {
                value = StringUtil.stripControlCharacters((String) entry.getValue());
            }
            if (key != null && !key.trim().isEmpty() && !Strings.isNullOrEmpty(value)) {
                if (key.length() > ContactConstants.MAX_FIELD_NAME_LENGTH) {
                    throw ServiceException.INVALID_REQUEST("too big filed name", null);
                } else if (value.length() > ContactConstants.MAX_FIELD_VALUE_LENGTH) {
                    throw MailServiceException.CONTACT_TOO_BIG(ContactConstants.MAX_FIELD_VALUE_LENGTH, value.length());
                }
                map.put(key, value);
            }
        }
        if (map.isEmpty()) {
            throw ServiceException.INVALID_REQUEST("contact must have fields", null);
        } else if (map.size() > ContactConstants.MAX_FIELD_COUNT) {
            throw ServiceException.INVALID_REQUEST("too many fields", null);
        }
        contactFields = map;

        // Initialize attachments.
        if (sharedStream != null) {
            InputStream contentStream = null;
            try {
                // Parse attachments.
                contentStream = getContentStream();
                contactAttachments = parseBlob(contentStream);
                for (Attachment attach : contactAttachments) {
                    contactFields.remove(attach.getName());
                }
                initializeSizeAndDigest();
            } catch (MessagingException me) {
                throw MailServiceException.MESSAGE_PARSE_ERROR(me);
            } catch (IOException ioe) {
                throw MailServiceException.MESSAGE_PARSE_ERROR(ioe);
            } finally {
                ByteUtil.closeStream(contentStream);
            }
        }
    }

    private void initializeSizeAndDigest() throws IOException {
        CalculatorStream calc = new CalculatorStream(getContentStream());
        size = ByteUtil.getDataLength(calc);
        digest = calc.getDigest();
    }

    private static MimeMessage generateMimeMessage(List<Attachment> attachments)
    throws MessagingException {
        MimeMessage mm = new Mime.FixedMimeMessage(JMSession.getSession());
        MimeMultipart multi = new JavaMailMimeMultipart("mixed");
        int part = 1;
        for (Attachment attach : attachments) {
            ContentDisposition cdisp = new ContentDisposition(Part.ATTACHMENT);
            cdisp.setParameter("filename", attach.getFilename()).setParameter("field", attach.getName());

            MimeBodyPart bp = new JavaMailMimeBodyPart();
            bp.addHeader("Content-Disposition", cdisp.toString());
            bp.addHeader("Content-Type", attach.getContentType());
            bp.addHeader("Content-Transfer-Encoding", MimeConstants.ET_8BIT);
            bp.setDataHandler(attach.getDataHandler());
            multi.addBodyPart(bp);

            attach.setPartName(Integer.toString(part++));
        }
        mm.setContent(multi);
        mm.saveChanges();

        return mm;
    }

    private static List<Attachment> parseBlob(InputStream in) throws ServiceException, MessagingException, IOException {
        MimeMessage mm = new Mime.FixedMimeMessage(JMSession.getSession(), in);
        MimeMultipart multi = null;
        try {
            multi = (MimeMultipart)mm.getContent();
        } catch (ClassCastException x) {
            throw ServiceException.FAILURE("MimeMultipart content expected but got " + mm.getContent().toString(), x);
        }

        List<Attachment> attachments = new ArrayList<Attachment>(multi.getCount());
        for (int i = 1; i <= multi.getCount(); i++) {
            MimeBodyPart bp = (MimeBodyPart) multi.getBodyPart(i - 1);
            ContentDisposition cdisp = new ContentDisposition(bp.getHeader("Content-Disposition", null));

            Attachment attachment = new Attachment(bp.getDataHandler(), cdisp.getParameter("field"));
            attachment.setPartName(Integer.toString(i));
            attachments.add(attachment);
        }
        return attachments;
    }

    public Map<String, String> getFields() {
        return contactFields;
    }

    public boolean hasAttachment() {
        return contactAttachments != null && !contactAttachments.isEmpty();
    }

    public List<Attachment> getAttachments() {
        return contactAttachments;
    }

    /**
     * Returns the stream to this contact's blob, or <tt>null</tt> if it has no attachments.
     */
    public InputStream getContentStream() throws IOException {
        if (sharedStream != null) {
            return ((SharedInputStream) sharedStream).newStream(0, -1);
        }
        if (mimeMessage != null) {
            return Mime.getInputStream(mimeMessage);
        }
        return null;
    }

    public long getSize() {
        return size;
    }

    public String getDigest() {
        return digest;
    }

    public ParsedContact modify(Map<String, String> fieldDelta, List<Attachment> attachDelta) throws ServiceException {
        if (attachDelta != null && !attachDelta.isEmpty()) {
            for (Attachment attach : attachDelta) {
                // make sure we don't have anything referenced in both fieldDelta and attachDelta
                fieldDelta.remove(attach.getName());

                // add the new attachments to the contact
                removeAttachment(attach.getName());
                if (contactAttachments == null) {
                    contactAttachments = new ArrayList<Attachment>(attachDelta.size());
                }
                contactAttachments.add(attach);
            }
        }

        for (Map.Entry<String, String> entry : fieldDelta.entrySet()) {
            String name  = StringUtil.stripControlCharacters(entry.getKey());
            String value = StringUtil.stripControlCharacters(entry.getValue());
            if (name != null && !name.trim().equals("")) {
                // kill any attachment with that field name
                removeAttachment(name);

                // and replace the field appropriately
                if (value == null || value.equals(""))
                    contactFields.remove(name);
                else
                    contactFields.put(name, value);
            }
        }

        if (contactFields.isEmpty())
            throw ServiceException.INVALID_REQUEST("contact must have fields", null);

        digest = null;
        mZDocuments = null;

        if (contactAttachments != null) {
            try {
                mimeMessage = generateMimeMessage(contactAttachments);

                // Original stream is now stale.
                ByteUtil.closeStream(sharedStream);
                sharedStream = null;

                initializeSizeAndDigest();
            } catch (MessagingException me) {
                throw MailServiceException.MESSAGE_PARSE_ERROR(me);
            } catch (IOException e) {
                throw MailServiceException.MESSAGE_PARSE_ERROR(e);
            }
        } else {
            // No attachments.  Wipe out any previous reference to a blob.
            ByteUtil.closeStream(sharedStream);
            sharedStream = null;
            mimeMessage = null;
            size = 0;
            digest = null;
        }

        return this;
    }

    private void removeAttachment(String name) {
        if (contactAttachments == null) {
            return;
        }
        for (Iterator<Attachment> it = contactAttachments.iterator(); it.hasNext(); ) {
            if (it.next().getName().equals(name)) {
                it.remove();
            }
        }
        if (contactAttachments.isEmpty()) {
            contactAttachments = null;
        }
    }


    public ParsedContact analyze(Mailbox mbox) throws ServiceException {
        try {
            analyzeContact(mbox.getAccount(), mbox.attachmentsIndexingEnabled());
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            ZimbraLog.index.warn("exception while analyzing contact; attachments will be partially indexed", e);
        }
        return this;
    }

    boolean mHasTemporaryAnalysisFailure = false;
    public boolean hasTemporaryAnalysisFailure() { return mHasTemporaryAnalysisFailure; }

    private void analyzeContact(Account acct, boolean indexAttachments) throws ServiceException {
        if (mZDocuments != null)
            return;

        mZDocuments = new ArrayList<IndexDocument>();
        StringBuilder attachContent = new StringBuilder();

        int numParseErrors = 0;
        ServiceException conversionError = null;
        if (contactAttachments != null) {
            for (Attachment attach: contactAttachments) {
                try {
                    analyzeAttachment(attach, attachContent, indexAttachments);
                } catch (MimeHandlerException e) {
                    numParseErrors++;
                    String part = attach.getPartName();
                    String ctype = attach.getContentType();
                    ZimbraLog.index.warn("Parse error on attachment " + part + " (" + ctype + ")", e);
                    if (conversionError == null && ConversionException.isTemporaryCauseOf(e)) {
                        conversionError = ServiceException.FAILURE("failed to analyze part", e.getCause());
                        mHasTemporaryAnalysisFailure = true;
                    }
                } catch (ObjectHandlerException e) {
                    numParseErrors++;
                    String part = attach.getPartName();
                    String ctype = attach.getContentType();
                    ZimbraLog.index.warn("Parse error on attachment " + part + " (" + ctype + ")", e);
                }
            }
        }

        mZDocuments.add(getPrimaryDocument(acct, attachContent.toString()));
    }

    public List<IndexDocument> getLuceneDocuments(Mailbox mbox) throws ServiceException {
        analyze(mbox);
        return mZDocuments;
    }

    private void analyzeAttachment(Attachment attach, StringBuilder contentText, boolean indexAttachments)
    throws MimeHandlerException, ObjectHandlerException, ServiceException {
        String ctype = attach.getContentType();
        MimeHandler handler = MimeHandlerManager.getMimeHandler(ctype, attach.getFilename());
        assert(handler != null);

        if (handler.isIndexingEnabled()) {
            handler.init(attach);
            handler.setPartName(attach.getPartName());
            handler.setFilename(attach.getFilename());
            handler.setSize(attach.getSize());

            if (indexAttachments && !DebugConfig.disableIndexingAttachmentsTogether) {
                // add ALL TEXT from EVERY PART to the toplevel body content.
                // This is necessary for queries with multiple words -- where
                // one word is in the body and one is in a sub-attachment.
                //
                // If attachment indexing is disabled, then we only add the main body and
                // text parts...
                contentText.append(contentText.length() == 0 ? "" : " ").append(handler.getContent());
            }

            if (indexAttachments && !DebugConfig.disableIndexingAttachmentsSeparately) {
                // Each non-text MIME part is also indexed as a separate
                // Lucene document.  This is necessary so that we can tell the
                // client what parts match if a search matched a particular
                // part.
                org.apache.lucene.document.Document doc = handler.getDocument();
                if (doc != null) {
                    IndexDocument idoc = new IndexDocument(doc);
                    idoc.addSortSize(attach.getSize());
                    mZDocuments.add(idoc);
                }
            }
        }
    }

    private static void appendContactField(StringBuilder sb, ParsedContact contact, String fieldName) {
        String value = contact.getFields().get(fieldName);
        if (!Strings.isNullOrEmpty(value)) {
            sb.append(value).append(' ');
        }
    }

    private IndexDocument getPrimaryDocument(Account acct, String contentStrIn) throws ServiceException {

        StringBuilder contentText = new StringBuilder();

        String emailFields[] = Contact.getEmailFields(acct);

        FieldTokenStream fields = new FieldTokenStream();
        for (Map.Entry<String, String> entry : getFields().entrySet()) {

            // do not index SMIME certificate fields
            if (Contact.isSMIMECertField(entry.getKey())) {
                continue;
            }

            if (!Contact.isEmailField(emailFields, entry.getKey())) { // skip email addrs, they're added to CONTENT below
                if (!ContactConstants.A_fileAs.equalsIgnoreCase(entry.getKey()))
                    contentText.append(entry.getValue()).append(' ');
            }
            fields.add(entry.getKey(), entry.getValue());
        }

        // fetch all the 'email' addresses for this contact into a single concatenated string
        StringBuilder emails  = new StringBuilder();
        for (String email : Contact.getEmailAddresses(emailFields, getFields())) {
            emails.append(email).append(',');
        }

        RFC822AddressTokenStream to = new RFC822AddressTokenStream(emails.toString());
        String emailStrTokens = StringUtil.join(" ", to.getAllTokens());

        StringBuilder searchText = new StringBuilder(emailStrTokens).append(' ');
        appendContactField(searchText, this, ContactConstants.A_company);
        appendContactField(searchText, this, ContactConstants.A_phoneticCompany);
        appendContactField(searchText, this, ContactConstants.A_firstName);
        appendContactField(searchText, this, ContactConstants.A_phoneticFirstName);
        appendContactField(searchText, this, ContactConstants.A_lastName);
        appendContactField(searchText, this, ContactConstants.A_phoneticLastName);
        appendContactField(searchText, this, ContactConstants.A_nickname);
        appendContactField(searchText, this, ContactConstants.A_fullName);

        // rebuild contentText here with the emailStr FIRST, then the other text.
        // The email addresses should be first so that they have a higher search score than the other
        // text
        contentText = new StringBuilder(emailStrTokens).append(' ').append(contentText).append(' ').append(contentStrIn);

        IndexDocument doc = new IndexDocument();

        /* put the email addresses in the "To" field so they can be more easily searched */
        doc.addTo(to);

        /* put the name in the "From" field since the MailItem table uses 'Sender'*/
        doc.addFrom(new RFC822AddressTokenStream(Contact.getFileAsString(contactFields)));
        /* bug 11831 - put contact searchable data in its own field so wildcard search works better  */
        doc.addContactData(searchText.toString());
        doc.addContent(contentText.toString());
        doc.addPartName(LuceneFields.L_PARTNAME_CONTACT);

        // add key:value pairs to the structured FIELD Lucene field
        doc.addField(fields);

        return doc;
    }

}
