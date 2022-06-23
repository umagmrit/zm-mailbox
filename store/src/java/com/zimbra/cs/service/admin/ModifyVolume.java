/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2005, 2006, 2007, 2009, 2010, 2011, 2012, 2013, 2014, 2016 Synacor, Inc.
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

package com.zimbra.cs.service.admin;

import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.zimbra.common.service.ServiceException;
import com.zimbra.common.soap.Element;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.account.accesscontrol.AdminRight;
import com.zimbra.cs.account.accesscontrol.Rights.Admin;
import com.zimbra.cs.store.StoreManager;
import com.zimbra.cs.volume.Volume;
import com.zimbra.cs.volume.VolumeManager;
import com.zimbra.soap.JaxbUtil;
import com.zimbra.soap.ZimbraSoapContext;
import com.zimbra.soap.admin.message.ModifyVolumeRequest;
import com.zimbra.soap.admin.message.ModifyVolumeResponse;
import com.zimbra.soap.admin.type.VolumeInfo;
import com.zimbra.soap.admin.type.VolumeExternalInfo;
import com.zimbra.util.ExternalVolumeReader;

public final class ModifyVolume extends AdminDocumentHandler {

    @Override
    public Element handle(Element req, Map<String, Object> ctx) throws ServiceException {
        ZimbraSoapContext zsc = getZimbraSoapContext(ctx);
        return zsc.jaxbToElement(handle((ModifyVolumeRequest) zsc.elementToJaxb(req), ctx));
    }

    private ModifyVolumeResponse handle(ModifyVolumeRequest req, Map<String, Object> ctx) throws ServiceException {
        ZimbraSoapContext zsc = getZimbraSoapContext(ctx);
        checkRight(zsc, ctx, Provisioning.getInstance().getLocalServer(), Admin.R_manageVolume);

        VolumeManager mgr = VolumeManager.getInstance();
        VolumeInfo volInfo = req.getVolumeInfo();
        Volume vol = mgr.getVolume(volInfo.getId());
        Volume.Builder builder = Volume.builder(vol);

        if (volInfo == null) {
            throw ServiceException.INVALID_REQUEST("must specify a volume Element", null);
        }
        StoreManager storeManager = StoreManager.getInstance();
        if (storeManager.supports(StoreManager.StoreFeature.CUSTOM_STORE_API, String.valueOf(volInfo.getId()))) {
            throw ServiceException.INVALID_REQUEST("Operation unsupported, use zxsuite to edit this volume", null);
        }

        // store type == 1, allow modification of all parameters
        if(vol.getStoreType().equals(Volume.StoreType.FILE_STORE)) {
            if (volInfo.getType() > 0) {
                builder.setType(volInfo.getType());
            }
            if (volInfo.getName() != null) {
                builder.setName(volInfo.getName());
            }
            if (volInfo.getRootPath() != null) {
                builder.setPath(volInfo.getRootPath(), true);
            }
            if (volInfo.getCompressBlobs() != null) {
                builder.setCompressBlobs(volInfo.getCompressBlobs());
            }
            if (volInfo.getCompressionThreshold() > 0) {
                builder.setCompressionThreshold(volInfo.getCompressionThreshold());
            }
        }
        // store type == 2, allow modification of only volume name
        else if (vol.getStoreType().equals(Volume.StoreType.EXTERNAL)) {
            if (volInfo.getName() != null) {
                builder.setName(volInfo.getName());
            }

            // Update ldap attribute after modification
            Provisioning prov = Provisioning.getInstance();
            try {
                ExternalVolumeReader extVolReader = new ExternalVolumeReader(prov);
                extVolReader.ModifyServerProperties(volInfo);
            }
            catch (JSONException e) {
                // LOG.error("Error while processing ldap attribute ServerExternalStoreConfig", e);
                // throw e;
            }
        }
        else {
            // Do nothing
        }
        mgr.update(builder.build());
        return new ModifyVolumeResponse();

    }

    @Override
    public void docRights(List<AdminRight> relatedRights, List<String> notes) {
        relatedRights.add(Admin.R_manageVolume);
    }

}
