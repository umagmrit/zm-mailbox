/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2014, 2016 Synacor, Inc.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software Foundation,
 * version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.common.collect.Maps;

import org.apache.jsieve.exception.SyntaxException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import com.zimbra.common.account.Key;
import com.zimbra.common.util.ArrayUtil;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.MockProvisioning;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.account.Server;
import com.zimbra.cs.filter.jsieve.SetVariable;
import com.zimbra.cs.filter.jsieve.Variables;
import com.zimbra.cs.lmtpserver.LmtpAddress;
import com.zimbra.cs.lmtpserver.LmtpEnvelope;
import com.zimbra.cs.mailbox.DeliveryContext;
import com.zimbra.cs.mailbox.Folder;
import com.zimbra.cs.mailbox.MailServiceException;
import com.zimbra.cs.mailbox.Mailbox;
import com.zimbra.cs.mailbox.MailboxManager;
import com.zimbra.cs.mailbox.MailboxTestUtil;
import com.zimbra.cs.mailbox.Message;
import com.zimbra.cs.mailbox.OperationContext;
import com.zimbra.cs.mime.ParsedMessage;
import com.zimbra.cs.service.util.ItemId;

public class SetVariableTest {
    private String filterScript = "";
   
    @BeforeClass
    public static void init() throws Exception {
        MailboxTestUtil.initServer();
        Provisioning prov = Provisioning.getInstance();
        Account acct = prov.createAccount("test1@zimbra.com", "secret", new HashMap<String, Object>());
        Server server = Provisioning.getInstance().getServer(acct);
        server.setSieveFeatureVariablesEnabled(true);
    }

    @Before
    public void setUp() throws Exception {
        MailboxTestUtil.clearData();
    }

    @Test
    public void testSetVar() {
        try {
            Account account = Provisioning.getInstance().getAccount(MockProvisioning.DEFAULT_ACCOUNT_ID);

            RuleManager.clearCachedRules(account);
            Mailbox mbox = MailboxManager.getInstance().getMailboxByAccount(account);

            filterScript = "require [\"variables\"];\n"
                         + "set \"var\" \"hello\"\n;"
                         + "if header :matches \"Subject\" \"*\" {\n"
                         + "  tag \"${var}\";\n"
                         + "}\n";
            account.setMailSieveScript(filterScript);
            String raw = "From: sender@zimbra.com\n"
                       + "To: test1@zimbra.com\n"
                       + "Subject: Test\n"
                       + "\n"
                       + "Hello World.";
            List<ItemId> ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                    new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                    Mailbox.ID_FOLDER_INBOX, true);
            Assert.assertEquals(1, ids.size());
            Message msg = mbox.getMessageById(null, ids.get(0).getId());
            Assert.assertEquals("hello", ArrayUtil.getFirstElement(msg.getTags()));

        } catch (Exception e) {
            e.printStackTrace();
            fail("No exception should be thrown");
        }
    }

    @Test
    public void testSetVarAndUseInHeader() {
        try {
            Account account = Provisioning.getInstance().getAccount(MockProvisioning.DEFAULT_ACCOUNT_ID);
            RuleManager.clearCachedRules(account);
            Mailbox mbox = MailboxManager.getInstance().getMailboxByAccount(account);
            filterScript = "require [\"variables\"];\n"
                         + "set \"var\" \"hello\";\n"
                         + "if header :contains \"Subject\" \"${var}\" {\n"
                         + "  tag \"blue\";\n"
                         + "}\n";
            account.setMailSieveScript(filterScript);
            String raw = "From: sender@zimbra.com\n"
                       + "To: test1@zimbra.com\n"
                       + "Subject: hello version 1.0 is out\n"
                       + "\n"
                       + "Hello World.";
            List<ItemId> ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                    new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                    Mailbox.ID_FOLDER_INBOX, true);
            Assert.assertEquals(1, ids.size());
            Message msg = mbox.getMessageById(null, ids.get(0).getId());
            Assert.assertEquals("blue", ArrayUtil.getFirstElement(msg.getTags()));

        } catch (Exception e) {
            fail("No exception should be thrown");
        }
    }

    @Test
    public void testSetVarAndUseInAction() {
        try {
            Account account = Provisioning.getInstance().getAccount(MockProvisioning.DEFAULT_ACCOUNT_ID);
            RuleManager.clearCachedRules(account);
            Mailbox mbox = MailboxManager.getInstance().getMailboxByAccount(account);
            filterScript = "require [\"variables\"];\n"
                         + "if header :matches \"Subject\" \"*\"{\n"
                         + "  set \"var\" \"hello\";\n"
                         + "  tag \"${var}\";\n"
                         + "}\n";
            account.setMailSieveScript(filterScript);
            String raw = "From: sender@zimbra.com\n"
                       + "To: test1@zimbra.com\n"
                       + "Subject: hello version 1.0 is out\n"
                       + "\n"
                       + "Hello World.";
            List<ItemId> ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                    new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                    Mailbox.ID_FOLDER_INBOX, true);
            Assert.assertEquals(1, ids.size());
            Message msg = mbox.getMessageById(null, ids.get(0).getId());
            Assert.assertEquals("hello", ArrayUtil.getFirstElement(msg.getTags()));

        } catch (Exception e) {
            fail("No exception should be thrown");
        }
    }

    @Test
    public void testReplacement() {
        Map<String, String> variables = new TreeMap<String, String>();
        Map<String, String> testCases = new TreeMap<String, String>();
        // RFC 5229 Section 3. Examples
        variables.put("company", "ACME");
        variables.put("foo", "bar");

        testCases.put("${full}", "");
        testCases.put("${company}", "ACME");
        testCases.put("${BAD${Company}", "${BADACME");
        testCases.put("${President, ${Company} Inc.}", "${President, ACME Inc.}");
        testCases.put("${company", "${company");
        testCases.put("${${company}}", "${ACME}");
        testCases.put("${${${company}}}", "${${ACME}}");
        testCases.put("${company}.${company}.${company}", "ACME.ACME.ACME");
        testCases.put("&%${}!", "&%${}!");
        testCases.put("${doh!}", "${doh!}");
        testCases.put("${fo\\o}",   "bar");   /* ${foo}   */
        testCases.put("${fo\\\\o}", "");      /* ${fo\\o} */
        testCases.put("\\${foo}",   "bar");   /* ${foo}   */
        testCases.put("\\\\${foo}", "\\bar"); /* \\${foo} */

        // More examples from RFC 5229 Section 3. and RFC 5228 Section 8.1.
        // variable-ref        =  "${" [namespace] variable-name "}"
        // namespace           =  identifier "." *sub-namespace
        // sub-namespace       =  variable-name "."
        // variable-name       =  num-variable / identifier
        // num-variable        =  1*DIGIT
        // identifier          = (ALPHA / "_") *(ALPHA / DIGIT / "_")
        variables.put("a.b", "おしらせ");
        variables.put("c_d", "C");
        variables.put("1", "One");
        variables.put("23", "twenty three");
        variables.put("uppercase", "upper case");

        testCases.put("${a.b}", "おしらせ");
        testCases.put("${c_d}", "C");
        testCases.put("${1}", "One");
        testCases.put("${23}", "${23}");     // Not defined
        testCases.put("${123}", "${123}");   // Invalid variable name
        testCases.put("${a.b} ${COMpANY} ${c_d}hao!", "おしらせ ACME Chao!");
        testCases.put("${a.b} ${def} ${c_d}hao!", "おしらせ  Chao!"); // 1st valid variable, 2nd undefined, 3rd valid variable
        testCases.put("${upperCase}", "upper case");
        testCases.put("${UPPERCASE}", "upper case");
        testCases.put("${uppercase}", "upper case");

        for (Map.Entry<String, String> entry : testCases.entrySet()) {
            String result = Variables.leastGreedyReplace(variables, entry.getKey());
            Assert.assertEquals(entry.getValue(), result);
        }
    }


    @Test
    public void testSetVarWithModifiersValid() {
        try {
            Account account = Provisioning.getInstance().getAccount(MockProvisioning.DEFAULT_ACCOUNT_ID);
            RuleManager.clearCachedRules(account);
            Mailbox mbox = MailboxManager.getInstance().getMailboxByAccount(account);
            filterScript = "require [\"variables\"];\n"
                         + "set \"var\" \"hello\";\n"
                         + "if header :matches \"Subject\" \"*\" {\n"
                         + "  tag \"${var}\";\n"
                         + "}\n";
            account.setMailSieveScript(filterScript);
            String raw = "From: sender@zimbra.com\n"
                       + "To: test1@zimbra.com\n"
                       + "Subject: Test\n"
                       + "\n"
                       + "Hello World.";
            List<ItemId> ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                    new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                    Mailbox.ID_FOLDER_INBOX, true);
            Message msg = mbox.getMessageById(null, ids.get(0).getId());
            Assert.assertEquals("hello", ArrayUtil.getFirstElement(msg.getTags()));

            RuleManager.clearCachedRules(account);
            filterScript = "require [\"variables\"];\n"
                         + "set :length \"var\" \"hello\";\n"
                         + "if header :matches \"Subject\" \"*\" {\n"
                         + "  tag \"${var}\";\n"
                         + "}\n";
            account.setMailSieveScript(filterScript);
            ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                    new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                    Mailbox.ID_FOLDER_INBOX, true);
            msg = mbox.getMessageById(null, ids.get(0).getId());
            Assert.assertEquals("5", ArrayUtil.getFirstElement(msg.getTags()));

            RuleManager.clearCachedRules(account);
            filterScript = "require [\"variables\"];\n"
                         + "set :lower \"var\" \"heLLo\";\n"
                         + "if header :matches \"Subject\" \"*\" {\n"
                         + "  tag \"${var}\";\n"
                         + "}\n";
            account.setMailSieveScript(filterScript);
            ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                    new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                    Mailbox.ID_FOLDER_INBOX, true);
            msg = mbox.getMessageById(null, ids.get(0).getId());
            Assert.assertEquals("hello", ArrayUtil.getFirstElement(msg.getTags()));

            RuleManager.clearCachedRules(account);
            filterScript = "require [\"variables\"];\n"
                         + "set :upper \"var\" \"test\";\n"
                         + "if header :matches \"Subject\" \"*\" {\n"
                         + "  tag \"${var}\";\n"
                         + "}\n";
            account.setMailSieveScript(filterScript);
            ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                    new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                    Mailbox.ID_FOLDER_INBOX, true);
            msg = mbox.getMessageById(null, ids.get(0).getId());
            Assert.assertEquals("TEST", ArrayUtil.getFirstElement(msg.getTags()));

            RuleManager.clearCachedRules(account);
            filterScript = "require [\"variables\"];\n"
                         + "set :lowerfirst \"var\" \"WORLD\";\n"
                         + "if header :matches \"Subject\" \"*\" {\n"
                         + "  tag \"${var}\";\n"
                         + "}\n";
            account.setMailSieveScript(filterScript);
            ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                    new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                    Mailbox.ID_FOLDER_INBOX, true);
            msg = mbox.getMessageById(null, ids.get(0).getId());
            Assert.assertEquals("wORLD", ArrayUtil.getFirstElement(msg.getTags()));

            RuleManager.clearCachedRules(account);
            filterScript = "require [\"variables\"];\n"
                         + "set :UPPERFIRST \"var\" \"example\";\n"
                         + "if header :matches \"Subject\" \"*\" {\n"
                         + "  tag \"${var}\";\n"
                         + "}\n";
            account.setMailSieveScript(filterScript);
            ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                    new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                    Mailbox.ID_FOLDER_INBOX, true);
            msg = mbox.getMessageById(null, ids.get(0).getId());
            Assert.assertEquals("Example", ArrayUtil.getFirstElement(msg.getTags()));

            RuleManager.clearCachedRules(account);
            filterScript = "require [\"variables\"];\n"
                         + "set :encodeurl :lower \"body_param\" \"Safe body&evil=evilbody\";\n"
                         + "if header :matches \"Subject\" \"*\" {\n"
                         + "  tag \"${body_param}\";\n"
                         + "}\n";
            account.setMailSieveScript(filterScript);
            ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                    new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                    Mailbox.ID_FOLDER_INBOX, true);
            msg = mbox.getMessageById(null, ids.get(0).getId());
            Assert.assertEquals("safe+body%26evil%3Devilbody", ArrayUtil.getFirstElement(msg.getTags()));

        } catch (Exception e) {
            fail("No exception should be thrown");
        }
    }

    @Test
    public void testSetVarWithModifiersInValid() {
        try {
            Account account = Provisioning.getInstance().getAccount(MockProvisioning.DEFAULT_ACCOUNT_ID);
            RuleManager.clearCachedRules(account);
            Mailbox mbox = MailboxManager.getInstance().getMailboxByAccount(account);

            String raw = "From: sender@zimbra.com\n"
                       + "To: test1@zimbra.com\n"
                       + "Subject: Test\n"
                       + "\n"
                       + "Hello World.";
            try {
                filterScript = "require [\"variables\"];\n"
                             + "set \"hello\";\n";
                account.setMailSieveScript(filterScript);
                RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                        new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                        Mailbox.ID_FOLDER_INBOX, true);
            } catch (Exception e) {
                if (e instanceof SyntaxException) {
                    SyntaxException se = (SyntaxException) e;

                    assertTrue(se.getMessage().indexOf("Atleast 2 argument are needed. Found Arguments: [[hello]]") > -1);
                }
            }

            try {
                filterScript = "require [\"variables\"];\n"
                             + "set :lownner \"var\" \"hello\";\n";
                account.setMailSieveScript(filterScript);
                RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                        new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                        Mailbox.ID_FOLDER_INBOX, true);
            } catch (Exception e) {
                if (e instanceof SyntaxException) {
                    SyntaxException se = (SyntaxException) e;
                    assertTrue(se.getMessage().indexOf("Invalid variable modifier:") > -1);
                }
            }

            try {
                filterScript = "require [\"variables\"];\n"
                             + "set :lower \"var\";\n";
                account.setMailSieveScript(filterScript);
                RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                        new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                        Mailbox.ID_FOLDER_INBOX, true);
            } catch (Exception e) {
                if (e instanceof SyntaxException) {
                    SyntaxException se = (SyntaxException) e;
                    assertTrue(se.getMessage().indexOf("Invalid variable modifier:") > -1);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            fail("No exception should be thrown");
        }
    }

    @Test
    public void testApplyModifiers() {
        String [] modifiers = new String [SetVariable.OPERATIONS_IDX];
        modifiers[SetVariable.getIndex(SetVariable.ALL_LOWER_CASE)] =  SetVariable.ALL_LOWER_CASE;
        modifiers[SetVariable.getIndex(SetVariable.UPPERCASE_FIRST)] = SetVariable.UPPERCASE_FIRST;
        String value = SetVariable.applyModifiers("juMBlEd lETteRS", modifiers);
        Assert.assertEquals("Jumbled letters", value);

        modifiers = new String [SetVariable.OPERATIONS_IDX];
        modifiers[SetVariable.getIndex(SetVariable.STRING_LENGTH)] =  SetVariable.STRING_LENGTH;
        value = SetVariable.applyModifiers("juMBlEd lETteRS", modifiers);
        Assert.assertEquals("15", value);

        modifiers = new String [SetVariable.OPERATIONS_IDX];
        modifiers[SetVariable.getIndex(SetVariable.QUOTE_WILDCARD)] =  SetVariable.QUOTE_WILDCARD;
        modifiers[SetVariable.getIndex(SetVariable.ALL_UPPER_CASE)] =  SetVariable.ALL_UPPER_CASE;
        modifiers[SetVariable.getIndex(SetVariable.LOWERCASE_FIRST)] = SetVariable.LOWERCASE_FIRST;
        value = SetVariable.applyModifiers("j?uMBlEd*lETte\\RS", modifiers);

        Assert.assertEquals("j\\?UMBLED\\*LETTE\\\\RS", value);
    }

    //    set "a" "juMBlEd lETteRS";             => "juMBlEd lETteRS"
    //    set :length "b" "${a}";                => "15"
    //    set :lower "b" "${a}";                 => "jumbled letters"
    //    set :upperfirst "b" "${a}";            => "JuMBlEd lETteRS"
    //    set :upperfirst :lower "b" "${a}";
    @Test
    public void testModifier() {
        try {
            Account account = Provisioning.getInstance().getAccount(MockProvisioning.DEFAULT_ACCOUNT_ID);
            RuleManager.clearCachedRules(account);
            Mailbox mbox = MailboxManager.getInstance().getMailboxByAccount(account);
            filterScript = "require [\"variables\"];\n"
                         + "set \"a\" \"juMBlEd lETteRS\" ;\n"
                         + "set :length \"b\" \"${a}\";\n"
                         + "set :lower \"b\" \"${a}\";\n"
                         + "set :upperfirst \"c\" \"${b}\";"
                         + "set :upperfirst :lower \"d\" \"${c}\"; "
                         + "if header :matches \"Subject\" \"*\" {\n"
                         + "  tag \"${d}\";\n"
                         + "}\n";
            account.setMailSieveScript(filterScript);
            String raw = "From: sender@zimbra.com\n"
                       + "To: test1@zimbra.com\n"
                       + "Subject: Test\n"
                       + "\n"
                       + "Hello World.";
            List<ItemId> ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                    new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                    Mailbox.ID_FOLDER_INBOX, true);
            Message msg = mbox.getMessageById(null, ids.get(0).getId());
            Assert.assertEquals("Jumbled letters", ArrayUtil.getFirstElement(msg.getTags()));
        } catch (Exception e) {
            fail("No exception should be thrown");
        }
    }

    @Test
    public void testVariablesCombo() {
        try {
            Account account = Provisioning.getInstance().getAccount(MockProvisioning.DEFAULT_ACCOUNT_ID);
            RuleManager.clearCachedRules(account);
            Mailbox mbox = MailboxManager.getInstance().getMailboxByAccount(account);
            // set "company" "ACME";
            // set "a.b" "おしらせ"; (or any non-ascii characters)
            // set "c_d" "C";
            // set "1" "One"; ==> Should be ignored or error [Note 1]
            // set "23" "twenty three"; ==> Should be ignored or error [Note 1]
            // set "combination" "Hello ${company}!!";
            filterScript = "require [\"variables\"];\n"
                         + "set \"company\" \"おしらせ\" ;\n"
                         + "set  \"a.b\" \"${a}\";\n"
                         + "set  \"c_d\" \"C\";\n"
                         + "set  \"1\" \"One\";"
                         + "set  \"combination\" \"Hello ${company}!!\"; "
                         + "if header :matches \"Subject\" \"*\" {\n"
                         + "  tag \"${combination}\";\n"
                         + "}\n";
            account.setMailSieveScript(filterScript);
            String raw = "From: sender@zimbra.com\n"
                       + "To: test1@zimbra.com\n"
                       + "Subject: Test\n"
                       + "\n"
                       + "Hello World.";
            List<ItemId> ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                    new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                    Mailbox.ID_FOLDER_INBOX, true);
            Message msg = mbox.getMessageById(null, ids.get(0).getId());
            Assert.assertEquals("Hello おしらせ!!", ArrayUtil.getFirstElement(msg.getTags()));
        } catch (Exception e) {
            fail("No exception should be thrown");
        }
    }

    @Test
    public void testStringInterpretation() {
        try {
            Account account = Provisioning.getInstance().getAccount(MockProvisioning.DEFAULT_ACCOUNT_ID);
            RuleManager.clearCachedRules(account);
            Mailbox mbox = MailboxManager.getInstance().getMailboxByAccount(account);
            filterScript = "require [\"variables\"];\n"
                         + "set \"a\" \"juMBlEd lETteRS\";\n"
                         + "if header :matches \"Subject\" \"*\" {\n"
                         + "  tag \"${d}\";\n"
                         + "}\n";
            account.setMailSieveScript(filterScript);
            String raw = "From: sender@zimbra.com\n"
                       + "To: test1@zimbra.com\n"
                       + "Subject: Test\n"
                       + "\n"
                       + "Hello World.";
            try {
                List<ItemId> ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                        new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                        Mailbox.ID_FOLDER_INBOX, true);

                Message msg = mbox.getMessageById(null, ids.get(0).getId());
            } catch (MailServiceException e) {
                String t = e.getArgumentValue("name");
                assertTrue(e.getCode().equals("mail.INVALID_NAME"));
                assertEquals("", t);
            }

            RuleManager.clearCachedRules(account);
            filterScript = "require [\"variables\"];\n"
                         + "set \"a\" \"juMBlEd lETteRS\";\n"
                         + "if header :matches \"Subject\" \"*\" {\n"
                         + "  tag \"${}\";\n"
                         + "}";
            account.setMailSieveScript(filterScript);
            try {
                List<ItemId> ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                        new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                        Mailbox.ID_FOLDER_INBOX, true);
            } catch (MailServiceException e) {
                String t = e.getArgumentValue("name");
                assertTrue(e.getCode().equals("mail.INVALID_NAME"));
                assertEquals("${}", t);
            }
            RuleManager.clearCachedRules(account);
            filterScript = "require [\"variables\"];\n"
                         + "set \"ave\" \"juMBlEd lETteRS\";\n"
                         + "if header :matches \"Subject\" \"*\" {\n"
                         + "  tag \"${ave!}\";\n"
                         + "}\n";
            account.setMailSieveScript(filterScript);
            try {
                List<ItemId> ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                        new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                        Mailbox.ID_FOLDER_INBOX, true);
            } catch (MailServiceException e) {
                String t = e.getArgumentValue("name");
                assertTrue(e.getCode().equals("mail.INVALID_NAME"));
                assertEquals("${ave!}", t);
            }
        } catch (Exception e) {   
            fail("No exception should be thrown");
        }
    }

    @Test
    public void testStringTest() {
        try {
            Account account = Provisioning.getInstance().getAccount(MockProvisioning.DEFAULT_ACCOUNT_ID);   
            Mailbox mbox = MailboxManager.getInstance().getMailboxByAccount(account);

            String raw = "From: sender@zimbra.com\n"
                       + "To: test1@zimbra.com\n"
                       + "Subject: Test\n"
                       + "\n"
                       + "Hello World.";
            RuleManager.clearCachedRules(account);
            filterScript = "require [\"variables\"];\n"
                         + "set :lower :upperfirst \"name\" \"Joe\";\n"
                         + "if string :is :comparator \"i;ascii-numeric\" \"${name}\" [ \"Joe\", \"Hello\", \"User\" ]{\n"
                         + "  tag \"sales\";\n"
                         + "}\n";
            account.setMailSieveScript(filterScript);
            List<ItemId> ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                    new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                    Mailbox.ID_FOLDER_INBOX, true);
            Assert.assertEquals(1, ids.size());
            Message msg = mbox.getMessageById(null, ids.get(0).getId());
            Assert.assertEquals("sales", ArrayUtil.getFirstElement(msg.getTags()));

            RuleManager.clearCachedRules(account);
            filterScript = "require [\"variables\"];\n"
                         + "set :lower :upperfirst \"name\" \"Joe\";\n"
                         + "if string :is  \"${name}\" [ \"Joe\", \"Hello\", \"User\" ]{\n"
                         + "  tag \"sales2\";\n"
                         + "}\n";
            account.setMailSieveScript(filterScript);
            ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                    new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                    Mailbox.ID_FOLDER_INBOX, true);
            Assert.assertEquals(1, ids.size());
            msg = mbox.getMessageById(null, ids.get(0).getId());
            Assert.assertEquals("sales2", ArrayUtil.getFirstElement(msg.getTags()));

        } catch (Exception e) {   
            fail("No exception should be thrown");
        }
    }

    @Test
    public void testSetMatchVarAndUseInHeader() {
        try {
            Account account = Provisioning.getInstance().getAccount(MockProvisioning.DEFAULT_ACCOUNT_ID);
            RuleManager.clearCachedRules(account);
            Mailbox mbox = MailboxManager.getInstance().getMailboxByAccount(account);

            filterScript = "require [\"variables\"];\n"
                         + "if header :matches [\"To\", \"Cc\"] [\"coyote@**.com\",\"wile@**.com\"]{\n"
                         + "  log \"Match 1 ${1}\";\n"
                         + "  tag \"${1}\";\n"
                         + "}\n";
            account.setMailSieveScript(filterScript);
            String raw = "From: sender@zimbra.com\n"
                       + "To: coyote@ACME.Example.COM\n"
                       + "Subject: hello version 1.0 is out\n"
                       + "\n"
                       + "Hello World.";
            List<ItemId> ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                    new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                    Mailbox.ID_FOLDER_INBOX, true);
            Assert.assertEquals(1, ids.size());
            Message msg = mbox.getMessageById(null, ids.get(0).getId());
            Assert.assertEquals("ACME.Example", ArrayUtil.getFirstElement(msg.getTags()));

        } catch (Exception e) {
            fail("No exception should be thrown");
        }
    }

    @Test
    public void testSetVarSieveFeatureDisabled() {
        try {
            Account account = Provisioning.getInstance().getAccount(MockProvisioning.DEFAULT_ACCOUNT_ID);
            Server server = Provisioning.getInstance().getServer(account);
            server.setSieveFeatureVariablesEnabled(false);
            RuleManager.clearCachedRules(account);
            Mailbox mbox = MailboxManager.getInstance().getMailboxByAccount(account);

            filterScript = "require [\"variables\"];\n"
                         + "set \"var\" \"hello\";\n"
                         + "if header :matches \"Subject\" \"*\" {\n"
                         + "  tag \"${var}\";\n"
                         + "}\n";
            account.setMailSieveScript(filterScript);
            String raw = "From: sender@zimbra.com\n"
                       + "To: test1@zimbra.com\n"
                       + "Subject: Test\n"
                       + "\n"
                       + "Hello World.";
            List<ItemId> ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                    new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                    Mailbox.ID_FOLDER_INBOX, true);
            Assert.assertEquals(1, ids.size());
            Message msg = mbox.getMessageById(null, ids.get(0).getId());
            Assert.assertEquals("${var}", ArrayUtil.getFirstElement(msg.getTags()));

        } catch (Exception e) {
            e.printStackTrace();
            fail("No exception should be thrown");
        }
    }

    @Test
    public void testSetMatchVarAndUseInHeader2() {
        try {
            Account account = Provisioning.getInstance().getAccount(MockProvisioning.DEFAULT_ACCOUNT_ID);

            Map<String, Object> attrs = Maps.newHashMap();
            attrs = Maps.newHashMap();
            attrs.put(Provisioning.A_zimbraSieveFeatureVariablesEnabled, "TRUE");
            Provisioning.getInstance().getServer(account).modify(attrs);

            RuleManager.clearCachedRules(account);
            Mailbox mbox = MailboxManager.getInstance().getMailboxByAccount(account);

            filterScript = "require [\"variables\"];\n"
                         + "set \"var\" \"hello\";\n"
                         + "if header :matches \"Subject\" \"${var}\" {\n"
                         + "  tag \"${var} world!\";\n"
                         + "}\n";
            account.setMailSieveScript(filterScript);
            String raw = "From: sender@zimbra.com\n"
                       + "To: coyote@ACME.Example.COM\n"
                       + "Subject: hello\n"
                       + "\n"
                       + "Hello World.";
            List<ItemId> ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                    new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                    Mailbox.ID_FOLDER_INBOX, true);
            Assert.assertEquals(1, ids.size());
            Message msg = mbox.getMessageById(null, ids.get(0).getId());
            Assert.assertEquals("hello world!", ArrayUtil.getFirstElement(msg.getTags()));

        } catch (Exception e) {
            fail("No exception should be thrown" + e.getStackTrace());
        }
    }

    @Test
    public void testSetMatchVarAndFileInto() {
        try {
            Account account = Provisioning.getInstance().getAccount(MockProvisioning.DEFAULT_ACCOUNT_ID);
            RuleManager.clearCachedRules(account);
            Mailbox mbox = MailboxManager.getInstance().getMailboxByAccount(account);

            filterScript = "require [\"log\", \"variables\"];\n"
                         + "set \"sub\" \"test\";\n"
                         + "if header :contains \"subject\" \"${sub}\" {\n"
                         + "  log \"Subject has test\";\n"
                         + "  fileinto \"${sub}\";\n"
                         + "}";

            System.out.println(filterScript);
            account.setMailSieveScript(filterScript);
            String raw = "From: sender@in.telligent.com\n" 
                       + "To: coyote@ACME.Example.COM\n"
                       + "Subject: test\n" + "\n" + "Hello World.";
            List<ItemId> ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                       new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                       Mailbox.ID_FOLDER_INBOX, true);
            Assert.assertEquals(1, ids.size());
            Message msg = mbox.getMessageById(null, ids.get(0).getId());
            Folder folder  = mbox.getFolderById(null, msg.getFolderId());
            Assert.assertEquals("test", folder.getName());

            RuleManager.clearCachedRules(account);
            filterScript = "require [\"log\", \"variables\"];\n"
                         + "set \"sub\" \"test\";\n"
                         + "if header :contains \"subject\" \"Hello ${sub}\" {\n"
                         + "  log \"Subject has test\";\n"
                         + "  fileinto \"${sub}\";\n"
                         + "}";

            System.out.println(filterScript);
            account.setMailSieveScript(filterScript);
            raw = "From: sender@in.telligent.com\n" 
                + "To: coyote@ACME.Example.COM\n"
                + "Subject: Hello test\n"
                + "\n"
                + "Hello World.";
            ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                Mailbox.ID_FOLDER_INBOX, true);
                Assert.assertEquals(1, ids.size());
            msg = mbox.getMessageById(null, ids.get(0).getId());
            folder  = mbox.getFolderById(null, msg.getFolderId());
            Assert.assertEquals("test", folder.getName());

        } catch (Exception e) {
            fail("No exception should be thrown");
        }

    }
 
    @Ignore
    public void testSetMatchVarWithEnvelope() {
        LmtpEnvelope env = new LmtpEnvelope();
        LmtpAddress sender = new LmtpAddress("<tim@example.com>", new String[] { "BODY", "SIZE" }, null);
        LmtpAddress recipient = new LmtpAddress("<test@zimbra.com>", null, null);
        env.setSender(sender);
        env.addLocalRecipient(recipient);
        try {
            Account account = Provisioning.getInstance().getAccount(MockProvisioning.DEFAULT_ACCOUNT_ID);
            RuleManager.clearCachedRules(account);
            Mailbox mbox = MailboxManager.getInstance().getMailboxByAccount(account);

            filterScript = "require [\"log\", \"variables\", \"envelope\" ];\n"
                         + "if envelope :matches [\"To\"] \"*\" {\n"
                         + "  set \"rcptto\" \"${1}\";\n"
                         + "  log \":matches ==> ${1}\";\n"
                         + "  log \"variables ==> ${rcptto}\";\n"
                         + "  tag \"${rcptto}\";\n"
                         + "}";

            account.setMailSieveScript(filterScript);
            account.setMail("test@zimbra.com");
            String raw = "From: sender@in.telligent.com\n" 
                       + "To: coyote@ACME.Example.COM\n"
                       + "Subject: test\n"
                       + "\n"
                       + "Hello World.";
            List<ItemId> ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                new ParsedMessage(raw.getBytes(), false), 0, account.getName(), env, new DeliveryContext(),
                Mailbox.ID_FOLDER_INBOX, true);
            Assert.assertEquals(1, ids.size());
            Message msg = mbox.getMessageById(null, ids.get(0).getId());
            Folder folder  = mbox.getFolderById(null, msg.getFolderId());
            Assert.assertEquals("coyote@ACME.Example.COM", ArrayUtil.getFirstElement(msg.getTags()));
        } catch (Exception e) {
            fail("No exception should be thrown");
        }

    }

    @Ignore
    public void testSetMatchVarMultiLineWithEnvelope() {
        LmtpEnvelope env = new LmtpEnvelope();
        LmtpAddress sender = new LmtpAddress("<tim@example.com>", new String[] { "BODY", "SIZE" }, null);
        LmtpAddress recipient = new LmtpAddress("<test@zimbra.com>", null, null);
        env.setSender(sender);
        env.addLocalRecipient(recipient);
        try {
            Account account = Provisioning.getInstance().getAccount(MockProvisioning.DEFAULT_ACCOUNT_ID);
            RuleManager.clearCachedRules(account);
            Mailbox mbox = MailboxManager.getInstance().getMailboxByAccount(account);

            filterScript = "require [\"log\", \"variables\", \"envelope\" ];\n"
                         + "if envelope :matches [\"To\"] \"*\" {"
                         + "  set \"rcptto\" \"${1}\";\n"
                         + "}\n"
                         + "if header :matches [\"From\"] \"*\" {"
                         + "  set \"fromheader\" \"${1}\";\n"
                         + "}\n"
                         + "if header :matches [\"Subject\"] \"*\" {"
                         + "  set \"subjectheader\" \"${1}\";\n"
                         + "}\n"
                         + "set \"bodyparam\" text: # This is a comment\r\n"
                         + "Message delivered to  ${rcptto}\n"
                         + "Sent : ${fromheader}\n"
                         + "Subject : ${subjectheader} \n"
                         + ".\r\n"
                         + ";\n"
                         +"log \"${bodyparam}\"; \n";
 
            System.out.println(filterScript);
            account.setMailSieveScript(filterScript);
            account.setMail("test@zimbra.com");
            String raw = "From: sender@in.telligent.com\n" 
                       + "To: coyote@ACME.Example.COM\n"
                       + "Subject: test\n"
                       + "\n"
                       + "Hello World.";
            List<ItemId> ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                new ParsedMessage(raw.getBytes(), false), 0, account.getName(), env, new DeliveryContext(),
                Mailbox.ID_FOLDER_INBOX, true);
               
        } catch (Exception e) {
            fail("No exception should be thrown");
        }

    }
 
    @Ignore
    public void testSetMatchVarMultiLineWithEnvelope2() {
        LmtpEnvelope env = new LmtpEnvelope();
        LmtpAddress sender = new LmtpAddress("<tim@example.com>", new String[] { "BODY", "SIZE" }, null);
        LmtpAddress recipient = new LmtpAddress("<test@zimbra.com>", null, null);
        env.setSender(sender);
        env.addLocalRecipient(recipient);
        try {
            Account account = Provisioning.getInstance().getAccount(MockProvisioning.DEFAULT_ACCOUNT_ID);
            RuleManager.clearCachedRules(account);
            Mailbox mbox = MailboxManager.getInstance().getMailboxByAccount(account);
               
            filterScript = "require [\"log\", \"variables\", \"envelope\" ];\n"
                         + "if envelope :matches [\"To\"] \"*\" {"
                         + "  set \"rcptto\" \"${1}\";\n"
                         + "}\n"
                         + "if header :matches [\"Date\"] \"*\" {"
                         + "  set \"dateheader\" \"${1}\";\n"
                         + "}\n"
                         + "if header :matches [\"From\"] \"*\" {"
                         + "  set \"fromheader\" \"${1}\";\n"
                         + "}\n"
                         + "if header :matches [\"Subject\"] \"*\" {"
                         + "  set \"subjectheader\" \"${1}\";\n"
                         + "}\n"
                         + "if anyof(not envelope :is [\"From\"] \"\" ){\n"
                         + "  set \"subjectparam\" \"Notification\";\n"
                         + "  set \"bodyparam\" text: # This is a comment\r\n"
                         + "Message delivered to  ${rcptto}\n"
                         + "Sent : ${fromheader}\n"
                         + "Subject : ${subjectheader} \n"
                         + ".\r\n"
                         + "  ;\n"
                         + "  log \"${bodyparam}\"; \n"
                         + "  log \"subjectparam ==> [${subjectparam}]\";\n"
                         + "  log \"rcptto ==> [${rcptto}]\";\n"
                         + "  log \"mailfrom ==> [${mailfrom}]\";\n"
                         + "}\n";

            System.out.println(filterScript);
            account.setMailSieveScript(filterScript);
            account.setMail("test@zimbra.com");
            String raw = "From: sender@in.telligent.com\n" 
                       + "To: coyote@ACME.Example.COM\n"
                       + "Subject: test\n" + "\n" + "Hello World.";
            List<ItemId> ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                new ParsedMessage(raw.getBytes(), false), 0, account.getName(), env, new DeliveryContext(),
                Mailbox.ID_FOLDER_INBOX, true);

        } catch (Exception e) {
            fail("No exception should be thrown");
        }

    }
    
    @Test
    public void testSetMatchVarAndUseInHeaderForAddress() {
        try {
            Account account = Provisioning.getInstance().getAccount(MockProvisioning.DEFAULT_ACCOUNT_ID);
            RuleManager.clearCachedRules(account);
            Mailbox mbox = MailboxManager.getInstance().getMailboxByAccount(account);

            filterScript = "require [\"variables\"];\n" 
                         + "if address :comparator \"i;ascii-casemap\" :matches \"To\" \"coyote@**.com\"{\n"
                         + "  tag \"${1}\";\n"
                         + "}";

            account.setMailSieveScript(filterScript);
            String raw = "From: sender@in.telligent.com\n" 
                       + "To: coyote@ACME.Example.COM\n"
                       + "Subject: hello version 1.0 is out\n"
                       + "\n"
                       + "Hello World.";
            List<ItemId> ids = RuleManager.applyRulesToIncomingMessage(new OperationContext(mbox), mbox,
                new ParsedMessage(raw.getBytes(), false), 0, account.getName(), new DeliveryContext(),
                Mailbox.ID_FOLDER_INBOX, true);
            Assert.assertEquals(1, ids.size());
            Message msg = mbox.getMessageById(null, ids.get(0).getId());
            Assert.assertEquals("ACME.Example", ArrayUtil.getFirstElement(msg.getTags()));

        } catch (Exception e) {
            fail("No exception should be thrown");
        }

    }
}
