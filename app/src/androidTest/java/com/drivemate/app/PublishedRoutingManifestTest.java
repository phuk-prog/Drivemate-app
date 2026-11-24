package com.drivemate.app;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class PublishedRoutingManifestTest{
    @Test public void realManifestAndCorruptionRejected()throws Exception{
        byte[] raw;
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("published-routing-manifest.json");
            ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);raw=out.toByteArray();}
        JSONObject d=LocalRoutingPack.parseManifest(raw);
        assertEquals(2397354382L,d.getLong("gzip_bytes"));
        assertEquals(3,d.getJSONArray("parts").length());
        raw[0]^=1;
        try{LocalRoutingPack.parseManifest(raw);fail("accepted corrupted manifest");}catch(IOException expected){}
    }
}
