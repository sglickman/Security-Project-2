//Based on SnifferSSLSocketFactory.java from The Grinder distribution.
// The Grinder distribution is available at http://grinder.sourceforge.net/

package mitm;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.math.BigInteger;
import iaik.asn1.structures.Name;
import iaik.asn1.ObjectID;
import iaik.x509.X509Certificate;
import java.util.HashMap;
import iaik.asn1.structures.AlgorithmID;
import java.security.Key;
import java.util.GregorianCalendar;
import java.util.Calendar;


/**
 * MITMSSLSocketFactory is used to create SSL sockets.
 *
 * This is needed because the javax.net.ssl socket factory classes don't
 * allow creation of factories with custom parameters.
 *
 */
public final class MITMSSLSocketFactory implements MITMSocketFactory
{
	final ServerSocketFactory m_serverSocketFactory;
	final SocketFactory m_clientSocketFactory;
	final SSLContext m_sslContext;

	public KeyStore ks = null;

	/*
	 *
	 * We can't install our own TrustManagerFactory without messing
	 * with the security properties file. Hence we create our own
	 * SSLContext and initialise it. Passing null as the keystore
	 * parameter to SSLContext.init() results in a empty keystore
	 * being used, as does passing the key manager array obtain from
	 * keyManagerFactory.getInstance().getKeyManagers(). To pick up
	 * the "default" keystore system properties, we have to read them
	 * explicitly. UGLY, but necessary so we understand the expected
	 * properties.
	 *
	 */

	/**
	 * This constructor will create an SSL server socket factory
	 * that is initialized with a fixed CA certificate
	 */
	public MITMSSLSocketFactory()
		throws IOException,GeneralSecurityException
	{
		m_sslContext = SSLContext.getInstance("SSL");

		final KeyManagerFactory keyManagerFactory =
			KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());

		final String keyStoreFile = System.getProperty(JSSEConstants.KEYSTORE_PROPERTY);
		final char[] keyStorePassword = System.getProperty(JSSEConstants.KEYSTORE_PASSWORD_PROPERTY, "").toCharArray();
		final String keyStoreType = System.getProperty(JSSEConstants.KEYSTORE_TYPE_PROPERTY, "jks");

		final KeyStore keyStore;

		if (keyStoreFile != null) {
			keyStore = KeyStore.getInstance(keyStoreType);
			keyStore.load(new FileInputStream(keyStoreFile), keyStorePassword);

			this.ks = keyStore;
		} else {
			keyStore = null;
		}

		keyManagerFactory.init(keyStore, keyStorePassword);

		m_sslContext.init(keyManagerFactory.getKeyManagers(),
						  new TrustManager[] { new TrustEveryone() },
						  null);

		m_clientSocketFactory = m_sslContext.getSocketFactory();
		m_serverSocketFactory = m_sslContext.getServerSocketFactory();
	}

	/**
	 * This constructor will create an SSL server socket factory
	 * that is initialized with a dynamically generated server certificate
	 * that contains the specified common name.
	 */
	public MITMSSLSocketFactory(String remoteCN, BigInteger serialno)
		throws IOException,GeneralSecurityException, Exception
	{
		this();
		// TODO: replace this with code to generate a new
		// server certificate with common name remoteCN and serial number
		// serialno
		X509Certificate forged_cert = new X509Certificate();
    Name subject = new Name();
    String[] cn_parts = remoteCN.split(", ?");
    // This is because we get extra spaces when we don't have the " ?" part.
    // throws everything off.
    HashMap shortnames = new HashMap();
    shortnames.put("C", ObjectID.country);
    shortnames.put("CN", ObjectID.commonName);
    shortnames.put("O", ObjectID.organization);
    shortnames.put("L", ObjectID.locality);
    shortnames.put("ST", ObjectID.stateOrProvince);
    shortnames.put("DC", ObjectID.domainComponent);
    shortnames.put("OU", ObjectID.organizationalUnit);
    shortnames.put("STREET", ObjectID.streetAddress);
    shortnames.put("SN", ObjectID.surName);
    shortnames.put("T", ObjectID.title);

    for (int i = 0; i < cn_parts.length; i++) {
      String[] pieces = cn_parts[i].split("=");
      if (shortnames.containsKey(pieces[0])) {
        subject.addRDN((ObjectID)shortnames.get(pieces[0]), pieces[1]);
      }
    }
		//we have this.ks available to generate principal
		//what happens if we use ks to create a java cert which we then use to create
		//a x509 cert that we can modify the DN and serialNUmber of
		String ksalias = ks.aliases().nextElement();
		System.out.println(ksalias);
		X509Certificate my_cert = new X509Certificate(ks.getCertificate(ksalias).getEncoded());
		forged_cert.setIssuerDN(subject);
		forged_cert.setSubjectDN(subject);
		forged_cert.setSerialNumber(serialno);
		forged_cert.setPublicKey(ks.getCertificate(ksalias).getPublicKey());
        GregorianCalendar date = (GregorianCalendar)Calendar.getInstance();
        forged_cert.setValidNotBefore(date.getTime());
        date.add(Calendar.MONTH, 6);
        forged_cert.setValidNotAfter(date.getTime());
		final char[] keyStorePassword = System.getProperty(JSSEConstants.KEYSTORE_PASSWORD_PROPERTY, "").toCharArray();
		Key myKey = ks.getKey(ksalias, keyStorePassword);
		
		forged_cert.sign(AlgorithmID.sha256WithRSAEncryption, (PrivateKey)myKey);
		ks.setCertificateEntry("forgedcert", forged_cert);
		// iaik.x509.X509Certificate

		// To convert from Java cert. to this, use new X509Certificate(javaCert.getEncoded())
		// Signing: cert.sign(AlgorithID.sha256withRSAEncryption, issuerPK)
		// See iaik.asn1.structures.Name <http://iaik.asn1.structures.Name>  (implements Principal)
		// For extracting info (e.j., common name) from server's DN (domain name), use cert.getSubjectDN(), 

	}

	public final ServerSocket createServerSocket(String localHost,
												 int localPort,
												 int timeout)
		throws IOException
	{
		final SSLServerSocket socket =
			(SSLServerSocket)m_serverSocketFactory.createServerSocket(
																	  localPort, 50, InetAddress.getByName(localHost));

		socket.setSoTimeout(timeout);

		socket.setEnabledCipherSuites(socket.getSupportedCipherSuites());

		return socket;
	}

	public final Socket createClientSocket(String remoteHost, int remotePort)
		throws IOException
	{
		final SSLSocket socket =
			(SSLSocket)m_clientSocketFactory.createSocket(remoteHost,
														  remotePort);

		socket.setEnabledCipherSuites(socket.getSupportedCipherSuites());

		socket.startHandshake();

		return socket;
	}

	/**
	 * We're carrying out a MITM attack, we don't care whether the cert
	 * chains are trusted or not ;-)
	 *
	 */
	private static class TrustEveryone implements X509TrustManager
	{
		public void checkClientTrusted(java.security.cert.X509Certificate[] chain,
									   String authenticationType) {
		}

		public void checkServerTrusted(java.security.cert.X509Certificate[] chain,
									   String authenticationType) {
		}

		public java.security.cert.X509Certificate[] getAcceptedIssuers()
		{
			return null;
		}
	}
}
