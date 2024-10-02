/*
 * Copyright (c) (2005-2023), WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.keystore.mgt;

import org.apache.axiom.om.util.UUIDGenerator;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.wso2.carbon.base.ServerConfiguration;
import org.wso2.carbon.core.RegistryResources;
import org.wso2.carbon.core.util.CryptoUtil;
import org.wso2.carbon.keystore.mgt.util.RealmServiceHolder;
import org.wso2.carbon.keystore.mgt.util.RegistryServiceHolder;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.registry.core.session.UserRegistry;
import org.wso2.carbon.security.SecurityConstants;
import org.wso2.carbon.security.keystore.KeyStoreAdmin;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.utils.ServerConstants;
import org.wso2.carbon.utils.security.KeystoreUtils;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * This class is used to generate a key store for a tenant and store it in the governance registry.
 * This class also provides APIs for idp-mgt component to generate a trust store with a given name.
 */
public class KeyStoreGenerator {

    private static Log log = LogFactory.getLog(KeyStoreGenerator.class);
    private UserRegistry govRegistry;
    private int tenantId;
    private String tenantDomain;
    private String password;
    private static final String SIGNING_ALG = "Tenant.SigningAlgorithm";

    // Supported signature algorithms for public certificate generation.
    private static final String DSA_SHA1 = "SHA1withDSA";
    private static final String ECDSA_SHA1 = "SHA1withECDSA";
    private static final String ECDSA_SHA256 = "SHA256withECDSA";
    private static final String ECDSA_SHA384 = "SHA384withECDSA";
    private static final String ECDSA_SHA512 = "SHA512withECDSA";
    private static final String RSA_MD5 = "MD5withRSA";
    private static final String RSA_SHA1 = "SHA1withRSA";
    private static final String RSA_SHA256 = "SHA256withRSA";
    private static final String RSA_SHA384 = "SHA384withRSA";
    private static final String RSA_SHA512 = "SHA512withRSA";
    private static final String[] signatureAlgorithms = new String[]{
            DSA_SHA1, ECDSA_SHA1, ECDSA_SHA256, ECDSA_SHA384, ECDSA_SHA512, RSA_MD5, RSA_SHA1, RSA_SHA256,
            RSA_SHA384, RSA_SHA512
    };



    public KeyStoreGenerator(int  tenantId) throws KeyStoreMgtException {
        try {
            this.tenantId = tenantId;
            this.tenantDomain = getTenantDomainName();
            this.govRegistry = RegistryServiceHolder.getRegistryService().
                    getGovernanceSystemRegistry(tenantId);
            if(govRegistry == null){
                log.error("Governance registry instance is null");
                throw new KeyStoreMgtException("Governance registry instance is null");
            }
        } catch (RegistryException e) {
            String errorMsg = "Error while obtaining the governance registry for tenant : " +
                      tenantId;
            log.error(errorMsg, e);
            throw new KeyStoreMgtException(errorMsg, e);
        }
    }


    /**
     * This method first generates the keystore, then persist it in the gov.registry of that tenant
     *
     * @throws KeyStoreMgtException Error when generating or storing the keystore
     */
    public void generateKeyStore() throws KeyStoreMgtException {
        try {
            password = generatePassword();
            KeyStore keyStore = KeystoreUtils.getKeystoreInstance(KeystoreUtils.getKeyStoreFileType(tenantDomain));
            keyStore.load(null, password.toCharArray());
            X509Certificate pubCert = generateKeyPair(keyStore);
            persistKeyStore(keyStore, pubCert);
        } catch (Exception e) {
            String msg = "Error while instantiating a keystore";
            log.error(msg, e);
            throw new KeyStoreMgtException(msg, e);
        }
    }

    /**
     * This method first generates the keystore, then persist it in the gov.registry of that tenant
     *
     * @throws KeyStoreMgtException Error when generating or storing the keystore
     */
    public void generateTrustStore(String trustStoreName) throws KeyStoreMgtException {
        try {
            password = generatePassword();
            KeyStore keyStore = KeystoreUtils.getKeystoreInstance(KeystoreUtils.getTrustStoreFileType());
            keyStore.load(null, password.toCharArray());
            persistTrustStore(keyStore, trustStoreName);
        } catch (Exception e) {
            String msg = "Error while instantiating a keystore";
            log.error(msg, e);
            throw new KeyStoreMgtException(msg, e);
        }
    }
    
    /**
     * This method checks the existance of a keystore
     *
     * @param tenantId
     * @return
     * @throws KeyStoreMgtException
     */
    public boolean isKeyStoreExists(int tenantId) throws KeyStoreMgtException{

        String keyStoreName = KeystoreUtils.getKeyStoreFileLocation(tenantDomain);
        boolean isKeyStoreExists = false;
        try {
            isKeyStoreExists = govRegistry.resourceExists(RegistryResources.SecurityManagement.KEY_STORES + "/" + keyStoreName);
        } catch (RegistryException e) {
            String msg = "Error while checking the existance of keystore.  ";
            log.error(msg + e.getMessage());
        }
        return isKeyStoreExists;
    }

    /**
     * This method generates the keypair and stores it in the keystore
     *
     * @param keyStore A keystore instance
     * @return Generated public key for the tenant
     * @throws KeyStoreMgtException Error when generating key pair
     */
    private X509Certificate generateKeyPair(KeyStore keyStore) throws KeyStoreMgtException {
        try {
            CryptoUtil.getDefaultCryptoUtil();
            //generate key pair
            String keyGenerationAlgorithm = getKeyGenerationAlgorithm();
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(keyGenerationAlgorithm);
            int keySize = getKeySize(keyGenerationAlgorithm);
            if (keySize != 0) {
                keyPairGenerator.initialize(keySize);
            }
            KeyPair keyPair = keyPairGenerator.generateKeyPair();

            // Common Name and alias for the generated certificate
            String commonName = "CN=" + tenantDomain + ", OU=None, O=None L=None, C=None";

            //generate certificates
            X500Name distinguishedName = new X500Name(commonName);

            Date notBefore = new Date(System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 30);
            Date notAfter = new Date(System.currentTimeMillis() + (1000L * 60 * 60 * 24 * 365 * 10));

            SubjectPublicKeyInfo subPubKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded());
            BigInteger serialNumber = BigInteger.valueOf(new SecureRandom().nextInt());

            X509v3CertificateBuilder certificateBuilder = new X509v3CertificateBuilder(
                    distinguishedName,
                    serialNumber,
                    notBefore,
                    notAfter,
                    distinguishedName,
                    subPubKeyInfo
            );

            String algorithmName = getSignatureAlgorithm();
            JcaContentSignerBuilder signerBuilder =
                    new JcaContentSignerBuilder(algorithmName).setProvider(getJCEProvider());
            PrivateKey privateKey = keyPair.getPrivate();
            X509Certificate x509Cert = new JcaX509CertificateConverter().setProvider(getJCEProvider())
                    .getCertificate(certificateBuilder.build(signerBuilder.build(privateKey)));

            //add private key to KS
            keyStore.setKeyEntry(tenantDomain, keyPair.getPrivate(), password.toCharArray(),
                    new java.security.cert.Certificate[]{x509Cert});
            return x509Cert;
        } catch (Exception ex) {
            String msg = "Error while generating the certificate for tenant :" +
                         tenantDomain + ".";
            log.error(msg, ex);
            throw new KeyStoreMgtException(msg, ex);
        }

    }

    /**
     * Persist the keystore in the gov.registry
     *
     * @param keyStore created Keystore of the tenant
     * @param PKCertificate pub. key of the tenant
     * @throws KeyStoreMgtException Exception when storing the keystore in the registry
     */
    private void persistKeyStore(KeyStore keyStore, X509Certificate PKCertificate)
            throws KeyStoreMgtException {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            keyStore.store(outputStream, password.toCharArray());
            outputStream.flush();
            outputStream.close();

            String keyStoreName = generateKSNameFromDomainName();
            // Use the keystore using the keystore admin
            KeyStoreAdmin keystoreAdmin = new KeyStoreAdmin(tenantId, govRegistry);
            keystoreAdmin.addKeyStore(outputStream.toByteArray(), keyStoreName,
                                      password, " ", KeystoreUtils.getKeyStoreFileType(tenantDomain), password);

            //Create the pub. key resource
            Resource pubKeyResource = govRegistry.newResource();
            pubKeyResource.setContent(PKCertificate.getEncoded());
            pubKeyResource.addProperty(SecurityConstants.PROP_TENANT_PUB_KEY_FILE_NAME_APPENDER,
                                       generatePubKeyFileNameAppender());

            govRegistry.put(RegistryResources.SecurityManagement.TENANT_PUBKEY_RESOURCE, pubKeyResource);

            //associate the public key with the keystore
            govRegistry.addAssociation(RegistryResources.SecurityManagement.KEY_STORES + "/" + keyStoreName,
                                       RegistryResources.SecurityManagement.TENANT_PUBKEY_RESOURCE,
                                       SecurityConstants.ASSOCIATION_TENANT_KS_PUB_KEY);

        } catch (RegistryException e) {
            String msg = "Error when writing the keystore/pub.cert to registry";
            log.error(msg, e);
            throw new KeyStoreMgtException(msg, e);
        }
        catch (Exception e) {
            String msg = "Error when processing keystore/pub. cert to be stored in registry";
            log.error(msg, e);
            throw new KeyStoreMgtException(msg, e);
        }
    }

    /**
     * Persist the trust store in the gov.registry
     *
     * @param trustStore created trust store of the tenant
     * @throws KeyStoreMgtException Exception when storing the trust store in the registry
     */
    private void persistTrustStore(KeyStore trustStore, String trustStoreName) throws KeyStoreMgtException {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            trustStore.store(outputStream, password.toCharArray());
            outputStream.flush();
            outputStream.close();

            KeyStoreAdmin keystoreAdmin = new KeyStoreAdmin(tenantId, govRegistry);
            keystoreAdmin.addTrustStore(outputStream.toByteArray(), trustStoreName, password, " ",
                    KeystoreUtils.getTrustStoreFileType());
        } catch (Exception e) {
            String msg = "Error when processing keystore/pub. cert to be stored in registry";
            log.error(msg, e);
            throw new KeyStoreMgtException(msg, e);
        }
    }

    /**
     * This method is used to generate a random password for the generated keystore
     *
     * @return generated password
     */
    private String generatePassword() {
        SecureRandom random = new SecureRandom();
        String randString = new BigInteger(130, random).toString(12);
        return randString.substring(randString.length() - 10, randString.length());
    }

    /**
     * This method is used to generate a file name appender for the pub. cert, e.g.
     * example-com-343743.cert
     * @return generated string to be used as a file name appender
     */
    private String generatePubKeyFileNameAppender(){
        String uuid = UUIDGenerator.getUUID();
        return uuid.substring(uuid.length() - 6, uuid.length()-1);
    }

    /**
     * This method generates the key store file name from the Domain Name
     * @return
     */
    private String generateKSNameFromDomainName(){
        String ksName = tenantDomain.trim().replace(".", "-");
        return (ksName + KeystoreUtils.getExtensionByFileType(KeystoreUtils.StoreFileType.defaultFileType()));
    }

    private String getTenantDomainName() throws KeyStoreMgtException {
        RealmService realmService = RealmServiceHolder.getRealmService();
        if (realmService == null) {
            String msg = "Error in getting the domain name, realm service is null.";
            log.error(msg);
            throw new KeyStoreMgtException(msg);
        }
        try {
            return realmService.getTenantManager().getDomain(tenantId);
        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            String msg = "Error in getting the domain name for the tenant id: " + tenantId;
            log.error(msg, e);
            throw new KeyStoreMgtException(msg, e);
        }
    }

    private static String getJCEProvider() {

        String provider = ServerConfiguration.getInstance().getFirstProperty(ServerConstants.JCE_PROVIDER);
        if (!StringUtils.isBlank(provider)) {
            return provider;
        }
        return ServerConstants.JCE_PROVIDER_BC;
    }

    private static String getSignatureAlgorithm() {

        String algorithm = ServerConfiguration.getInstance().getFirstProperty(SIGNING_ALG);
        // Find in a list of supported signature algorithms.
        for (String supportedAlgorithm : signatureAlgorithms) {
            if (supportedAlgorithm.equalsIgnoreCase(algorithm)) {
                return supportedAlgorithm;
            }
        }
        return RSA_MD5;
    }

    private static String getKeyGenerationAlgorithm() {

        String signatureAlgorithm = getSignatureAlgorithm();
        // If the algorithm naming format is {digest}with{encryption}, we need to extract the encryption part.
        int withIndex = signatureAlgorithm.indexOf("with");
        if (withIndex != -1 && withIndex + 4 < signatureAlgorithm.length()) {
            return signatureAlgorithm.substring(withIndex + 4);
        } else {
            // The algorithm name is same as the encryption algorithm.
            // This need to be updated if more algorithms are supported.
            return signatureAlgorithm;
        }
    }

    private static int getKeySize(String algorithm) {

        // Initialize the key size according to the FIPS standard.
        // This need to be updated if more algorithms are supported.
        if ("ECDSA".equalsIgnoreCase(algorithm)) {
            return 384;
        } else if ("RSA".equalsIgnoreCase(algorithm) || "DSA".equalsIgnoreCase(algorithm)) {
            return 2048;
        }
        return 0;
    }

}
