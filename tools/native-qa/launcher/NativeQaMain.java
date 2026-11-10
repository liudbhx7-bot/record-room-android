/** Bootstrap the real host JCE before Robolectric installs its Android Conscrypt provider. */
public final class NativeQaMain {
    public static void main(String[] classes) throws Exception {
        javax.crypto.KeyGenerator generator=javax.crypto.KeyGenerator.getInstance("AES");generator.init(256);
        javax.crypto.Cipher cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,generator.generateKey());cipher.doFinal(new byte[]{1});
        // Private host implementation is absent on some supported JDKs.
        try { Class.forName("javax.crypto.JarVerifier"); } catch (ClassNotFoundException optionalHostClass) { }
        org.junit.runner.JUnitCore.main(classes);
    }
}
