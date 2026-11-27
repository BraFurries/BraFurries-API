package com.Brafurries.API.platform.drive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class GoogleDriveUploadPolicyTest {
    private final ObjectMapper json=new ObjectMapper();
    private static final String SESSION="d15410a1-af3b-4d6f-9941-f8ade811b110";

    private ObjectNode folder(String id,String parent) {
        var node=json.createObjectNode().put("id",id).put("mimeType","application/vnd.google-apps.folder")
            .put("ownedByMe",true).put("trashed",false).put("shared",false);
        if (parent!=null) node.putArray("parents").add(parent);
        node.putObject("capabilities").put("canAddChildren",true);
        return node;
    }
    private ObjectNode permissions() {
        var result=json.createObjectNode();
        result.putArray("permissions").addObject().put("id","owner-id").put("type","user").put("role","owner");
        return result;
    }
    @Test void acceptsOnlyGoogleResumableCapability() {
        String prefix="https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=";
        assertEquals(prefix+"valid_id",GoogleDriveUploadPolicy.sessionUri(prefix+"valid_id").toString());
        for (String invalid:new String[]{
            prefix+"valid_id#fragment",prefix+"x&upload_id=y",prefix+"x&access_token=abc",
            prefix.replace("www.googleapis.com","example.com")+"x",
            prefix.replace("www.googleapis.com","www.googleapis.com.evil.test")+"x",
            prefix.replace("https:","http:")+"x",prefix.replace("www.googleapis.com","www.googleapis.com:443")+"x",
            prefix.replace("www.googleapis.com","user@www.googleapis.com")+"x"})
            assertThrows(ResponseStatusException.class,()->GoogleDriveUploadPolicy.sessionUri(invalid));
    }
    @Test void privateStagingMustBeSiblingOfInstitutionalRoot() {
        var my=folder("my_drive",null);
        var root=folder("root_folder","my_drive");
        var staging=folder("staging_folder","my_drive");
        assertDoesNotThrow(()->GoogleDriveUploadPolicy.requirePrivateStaging(my,root,staging,permissions(),
            "root_folder","staging_folder"));
        var nested=folder("staging_folder","root_folder");
        assertThrows(ResponseStatusException.class,()->GoogleDriveUploadPolicy.requirePrivateStaging(my,root,nested,
            permissions(),"root_folder","staging_folder"));
        staging.put("shared",true);
        assertThrows(ResponseStatusException.class,()->GoogleDriveUploadPolicy.requirePrivateStaging(my,root,staging,
            permissions(),"root_folder","staging_folder"));
    }
    @Test void rejectsExtraOrIncompleteStagingPermissions() {
        var my=folder("my_drive",null);
        var root=folder("root_folder","my_drive");
        var staging=folder("staging_folder","my_drive");
        var grants=permissions();
        grants.withArray("permissions").addObject().put("id","other").put("type","anyone").put("role","reader");
        assertThrows(ResponseStatusException.class,()->GoogleDriveUploadPolicy.requirePrivateStaging(my,root,staging,
            grants,"root_folder","staging_folder"));
        var incomplete=permissions().put("nextPageToken","more");
        assertThrows(ResponseStatusException.class,()->GoogleDriveUploadPolicy.requirePrivateStaging(my,root,staging,
            incomplete,"root_folder","staging_folder"));
    }
    @Test void matchesFileAgainstServerReservationNotClientReceipt() {
        var file=json.createObjectNode().put("id","file_123").put("name","arte.png").put("mimeType","image/png")
            .put("ownedByMe",true).put("trashed",false).put("shared",false)
            .put("size","5368709120").put("version","1");
        file.putArray("parents").add("staging_folder");
        file.putObject("appProperties").put("brafurriesUploadSession",SESSION);
        assertDoesNotThrow(()->GoogleDriveUploadPolicy.requireStagedFile(file,"file_123",SESSION,"staging_folder",
            "arte.png","image/png",5368709120L));
        assertThrows(ResponseStatusException.class,()->GoogleDriveUploadPolicy.requireStagedFile(file,"other_file",SESSION,
            "staging_folder","arte.png","image/png",5368709120L));
        assertThrows(ResponseStatusException.class,()->GoogleDriveUploadPolicy.requireStagedFile(file,"file_123",SESSION,
            "staging_folder","arte.png","image/png",1));
        file.withObject("/appProperties").put("brafurriesUploadSession","wrong");
        assertThrows(ResponseStatusException.class,()->GoogleDriveUploadPolicy.requireStagedFile(file,"file_123",SESSION,
            "staging_folder","arte.png","image/png",5368709120L));
    }
    @Test void rejectsNativeConversionAndUnsafeNames() {
        assertTrue(GoogleDriveUploadPolicy.validName("Arte final 🐾.png"));
        assertFalse(GoogleDriveUploadPolicy.validName("../arquivo"));
        assertFalse(GoogleDriveUploadPolicy.validName("a"+(char)10+"b"));
        assertTrue(GoogleDriveUploadPolicy.validMime("application/octet-stream"));
        assertFalse(GoogleDriveUploadPolicy.validMime("application/vnd.google-apps.document"));
        assertFalse(GoogleDriveUploadPolicy.validMime("image/png; charset=utf-8"));
    }
}
