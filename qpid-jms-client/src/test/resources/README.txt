# The various SSL stores and certificates were created with the following commands:
# Seems to require the JDK8 keytool.

# Clean up existing files
# -----------------------
rm -f *.crt *.csr *.keystore *.truststore

# Create a key and self-signed certificate for the CA, to sign certificate requests and use for trust:
# ----------------------------------------------------------------------------------------------------
keytool -storetype pkcs12 -keystore ca-pkcs12.keystore -storepass password -keypass password -alias ca -genkey -dname "O=My Trusted Inc.,CN=my-ca.org" -validity 9999 -ext bc:c=ca:true
keytool -storetype pkcs12 -keystore ca-pkcs12.keystore -storepass password -alias ca -exportcert -rfc > ca.crt

# Create a key pair for the broker, and sign it with the CA:
# ----------------------------------------------------------
keytool -storetype pkcs12 -keystore broker-pkcs12.keystore -storepass password -keypass password -alias broker -genkey -dname "O=Server,CN=localhost" -validity 9999 -ext bc=ca:false -ext eku=sA

keytool -storetype pkcs12 -keystore broker-pkcs12.keystore -storepass password -alias broker -certreq -file broker.csr
keytool -storetype pkcs12 -keystore ca-pkcs12.keystore -storepass password -alias ca -gencert -rfc -infile broker.csr -outfile broker.crt -ext bc=ca:false -ext eku=sA

keytool -storetype pkcs12 -keystore broker-pkcs12.keystore -storepass password -keypass password -importcert -alias ca -file ca.crt -noprompt
keytool -storetype pkcs12 -keystore broker-pkcs12.keystore -storepass password -keypass password -importcert -alias broker -file broker.crt

# Create some alternative keystore types for testing:
# ---------------------------------------------------
keytool -importkeystore -srckeystore broker-pkcs12.keystore -destkeystore broker-jceks.keystore -srcstoretype pkcs12 -deststoretype jceks -srcstorepass password -deststorepass password
keytool -importkeystore -srckeystore broker-pkcs12.keystore -destkeystore broker-jks.keystore -srcstoretype pkcs12 -deststoretype jks -srcstorepass password -deststorepass password

# Create a key pair for the broker with an unexpected hostname, and sign it with the CA:
# --------------------------------------------------------------------------------------
keytool -storetype pkcs12 -keystore broker-wrong-host-jks.keystore -storepass password -keypass password -alias broker -genkey -dname "O=Server,CN=localhost" -validity 9999 -ext bc=ca:false -ext eku=sA

keytool -storetype pkcs12 -keystore broker-wrong-host-jks.keystore -storepass password -alias broker -certreq -file broker.csr
keytool -storetype pkcs12 -keystore ca-pkcs12.keystore -storepass password -alias ca -gencert -rfc -infile broker.csr -outfile broker.crt -ext bc=ca:false -ext eku=sA

keytool -storetype pkcs12 -keystore broker-wrong-host-jks.keystore -storepass password -keypass password -importcert -alias ca -file ca.crt -noprompt
keytool -storetype pkcs12 -keystore broker-wrong-host-jks.keystore -storepass password -keypass password -importcert -alias broker -file broker.crt

# Create trust stores for the broker, import the CA cert:
# -------------------------------------------------------
keytool -storetype pkcs12 -keystore broker-pkcs12.truststore -storepass password -keypass password -importcert -alias ca -file ca.crt -noprompt
keytool -importkeystore -srckeystore broker-pkcs12.truststore -destkeystore broker-jceks.truststore -srcstoretype pkcs12 -deststoretype jceks -srcstorepass password -deststorepass password
keytool -importkeystore -srckeystore broker-pkcs12.truststore -destkeystore broker-jks.truststore -srcstoretype pkcs12 -deststoretype jks -srcstorepass password -deststorepass password

# Create a key pair for the client, and sign it with the CA:
# ----------------------------------------------------------
keytool -storetype pkcs12 -keystore client-pkcs12.keystore -storepass password -keypass password -alias client -genkey -dname "O=Client,CN=client" -validity 9999 -ext bc=ca:false -ext eku=cA

keytool -storetype pkcs12 -keystore client-pkcs12.keystore -storepass password -alias client -certreq -file client.csr
keytool -storetype pkcs12 -keystore ca-pkcs12.keystore -storepass password -alias ca -gencert -rfc -infile client.csr -outfile client.crt -ext bc=ca:false -ext eku=cA

keytool -storetype pkcs12 -keystore client-pkcs12.keystore -storepass password -keypass password -importcert -alias ca -file ca.crt -noprompt
keytool -storetype pkcs12 -keystore client-pkcs12.keystore -storepass password -keypass password -importcert -alias client -file client.crt

# Create some alternative keystore types for testing:
# ---------------------------------------------------
keytool -importkeystore -srckeystore client-pkcs12.keystore -destkeystore client-jceks.keystore -srcstoretype pkcs12 -deststoretype jceks -srcstorepass password -deststorepass password
keytool -importkeystore -srckeystore client-pkcs12.keystore -destkeystore client-jks.keystore -srcstoretype pkcs12 -deststoretype jks -srcstorepass password -deststorepass password

# Create trust stores for the client, import the CA cert:
# -------------------------------------------------------
keytool -storetype pkcs12 -keystore client-pkcs12.truststore -storepass password -keypass password -importcert -alias ca -file ca.crt -noprompt
keytool -importkeystore -srckeystore client-pkcs12.truststore -destkeystore client-jceks.truststore -srcstoretype pkcs12 -deststoretype jceks -srcstorepass password -deststorepass password
keytool -importkeystore -srckeystore client-pkcs12.truststore -destkeystore client-jks.truststore -srcstoretype pkcs12 -deststoretype jks -srcstorepass password -deststorepass password

# Create a truststore with self-signed certificate for an alternative CA, to
# allow 'failure to trust' of certs signed by the original CA above:
# ------------------------------------------------------------------
keytool -storetype jks -keystore other-ca-jks.truststore -storepass password -keypass password -alias other-ca -genkey -dname "O=Other Trusted Inc.,CN=other-ca.org" -validity 9999 -ext bc:c=ca:true
keytool -storetype jks -keystore other-ca-jks.truststore -storepass password -alias other-ca -exportcert -rfc > other-ca.crt
keytool -storetype jks -keystore other-ca-jks.truststore -storepass password -alias other-ca -delete
keytool -storetype jks -keystore other-ca-jks.truststore -storepass password -keypass password -importcert -alias other-ca -file other-ca.crt -noprompt


# Create a store with multiple key pairs for the client to allow for alias selection:
# ----------------------------------------------------------
keytool -importkeystore -srckeystore client-pkcs12.keystore -destkeystore client-multiple-keys-jks.keystore -srcstoretype pkcs12 -deststoretype jks -srcstorepass password -deststorepass password

keytool -storetype jks -keystore client-multiple-keys-jks.keystore -storepass password -keypass password -alias client2 -genkey -dname "O=Client2,CN=client2" -validity 9999 -ext bc=ca:false -ext eku=cA

keytool -storetype jks -keystore client-multiple-keys-jks.keystore -storepass password -alias client2 -certreq -file client2.csr
keytool -storetype pkcs12 -keystore ca-pkcs12.keystore -storepass password -alias ca -gencert -rfc -infile client2.csr -outfile client2.crt -ext bc=ca:false -ext eku=cA
keytool -storetype jks -keystore client-multiple-keys-jks.keystore -storepass password -keypass password -importcert -alias client2 -file client2.crt
