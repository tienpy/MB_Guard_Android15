package com.mrtien.mbbankguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1GeneralizedTime;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.PrivateKeyUsagePeriod;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * ADB client that lives completely inside MB Guard.
 * The RSA identity is persisted in app-private SharedPreferences so Android's
 * Wireless Debugging pairing remains valid across app restarts.
 */
public final class LocalAdbConnectionManager extends AbsAdbConnectionManager {
    private static final String PREFS = "local_adb_identity";
    private static final String KEY_PRIVATE = "private_key_pkcs8";
    private static final String KEY_CERT = "certificate_der";

    private static LocalAdbConnectionManager instance;

    private final PrivateKey privateKey;
    private final Certificate certificate;

    public static synchronized LocalAdbConnectionManager getInstance(Context context) throws Exception {
        if (instance == null) {
            instance = new LocalAdbConnectionManager(context.getApplicationContext());
        }
        return instance;
    }

    private LocalAdbConnectionManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        KeyMaterial material = loadOrCreateIdentity(context);
        privateKey = material.privateKey;
        certificate = material.certificate;
    }

    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @Override
    protected String getDeviceName() {
        return "MB Guard";
    }

    private static KeyMaterial loadOrCreateIdentity(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String privateText = prefs.getString(KEY_PRIVATE, null);
        String certText = prefs.getString(KEY_CERT, null);

        if (privateText != null && certText != null) {
            try {
                byte[] privateBytes = Base64.decode(privateText, Base64.DEFAULT);
                PrivateKey privateKey = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
                byte[] certBytes = Base64.decode(certText, Base64.DEFAULT);
                Certificate certificate = CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(certBytes));
                return new KeyMaterial(privateKey, certificate);
            } catch (Throwable ignored) {
            }
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();
        PrivateKey privateKey = pair.getPrivate();
        PublicKey publicKey = pair.getPublic();

        Date notBefore = new Date(System.currentTimeMillis() - 60_000L);
        Date notAfter = new Date(System.currentTimeMillis() + 10L * 365L * 24L * 60L * 60L * 1000L);
        BigInteger serial = new BigInteger(63, new SecureRandom()).add(BigInteger.ONE);
        X500Name subject = new X500Name("CN=MB Guard Local ADB");

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, publicKey
        );
        JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
        builder.addExtension(
                Extension.subjectKeyIdentifier,
                false,
                extUtils.createSubjectKeyIdentifier(publicKey)
        );

        ASN1EncodableVector period = new ASN1EncodableVector();
        period.add(new DERTaggedObject(false, 0, new ASN1GeneralizedTime(notBefore)));
        period.add(new DERTaggedObject(false, 1, new ASN1GeneralizedTime(notAfter)));
        builder.addExtension(
                Extension.privateKeyUsagePeriod,
                false,
                PrivateKeyUsagePeriod.getInstance(new DERSequence(period))
        );

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(privateKey);
        Certificate certificate = new JcaX509CertificateConverter()
                .getCertificate(builder.build(signer));

        prefs.edit()
                .putString(KEY_PRIVATE, Base64.encodeToString(privateKey.getEncoded(), Base64.NO_WRAP))
                .putString(KEY_CERT, Base64.encodeToString(certificate.getEncoded(), Base64.NO_WRAP))
                .apply();

        return new KeyMaterial(privateKey, certificate);
    }

    private static final class KeyMaterial {
        final PrivateKey privateKey;
        final Certificate certificate;

        KeyMaterial(PrivateKey privateKey, Certificate certificate) {
            this.privateKey = privateKey;
            this.certificate = certificate;
        }
    }
}
