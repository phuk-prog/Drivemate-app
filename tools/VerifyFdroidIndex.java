import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.util.*;
import java.util.jar.*;
/** Verify the exact JSON entry and pin its actual leaf signing certificate. */
class VerifyFdroidIndex {
    public static void main(String[] args)throws Exception{
        if(args.length!=3)throw new IllegalArgumentException("jar fingerprint output");
        byte[] payload;
        try(JarFile jar=new JarFile(args[0],true)){
            if(jar.stream().filter(e->e.getName().equals("index-v1.json")).count()!=1)throw new SecurityException("Missing or duplicate signed index entry");
            JarEntry entry=jar.getJarEntry("index-v1.json");
            if(entry.getSize()<1||entry.getSize()>250_000_000)throw new SecurityException("Index exceeds size limit");
            try(InputStream in=jar.getInputStream(entry)){payload=in.readNBytes(250_000_001);}
            if(payload.length!=entry.getSize()||payload.length>250_000_000)throw new SecurityException("Unexpected signed index length");
            CodeSigner[] signers=entry.getCodeSigners();
            if(signers==null||signers.length!=1)throw new SecurityException("Index entry not signed by exactly one verified signer; check JDK disabled algorithms policy");
            Certificate cert=signers[0].getSignerCertPath().getCertificates().get(0);
            String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
            if(!fingerprint.equalsIgnoreCase(args[1]))throw new SecurityException("Unexpected index certificate SHA-256: "+fingerprint);
            System.out.println("Verified signed index entry and pinned certificate "+fingerprint);
        }
        Files.write(Path.of(args[2]),payload,StandardOpenOption.CREATE_NEW);
    }
}
