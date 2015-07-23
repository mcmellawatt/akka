/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.util

import java.io.InputStream
import java.security.{ SecureRandom, KeyStore }
import java.security.cert.{ CertificateFactory, Certificate }
import javax.net.ssl.{ SSLParameters, SSLContext, TrustManagerFactory, KeyManagerFactory }

import akka.http.scaladsl.HttpsContext

/**
 * These are HTTPS example configurations that take key material from the resources/key folder.
 */
object ExampleHttpContexts {
  val exampleServerContext = {
    val ks = KeyStore.getInstance("PKCS12")
    ks.load(resourceStream("keys/server.p12"), "abcdef".toCharArray)

    val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(ks, "abcdef".toCharArray)

    val context = SSLContext.getInstance("TLS")
    context.init(keyManagerFactory.getKeyManagers, null, new SecureRandom)

    HttpsContext(context)
  }
  val exampleClientContext = {
    val certStore = KeyStore.getInstance(KeyStore.getDefaultType)
    certStore.load(null, null)
    certStore.setCertificateEntry("ca", loadX509Certificate("keys/rootCA.crt"))

    val certManagerFactory = TrustManagerFactory.getInstance("SunX509")
    certManagerFactory.init(certStore)

    val context = SSLContext.getInstance("TLS")
    context.init(null, certManagerFactory.getTrustManagers, new SecureRandom)

    val params = new SSLParameters()
    Java6Compat.setEndpointIdentificationAlgorithm(params, "https")
    HttpsContext(context, sslParameters = Some(params))
  }

  def resourceStream(resourceName: String): InputStream = {
    val is = getClass.getClassLoader.getResourceAsStream(resourceName)
    require(is ne null, s"Resource $resourceName not found")
    is
  }

  def loadX509Certificate(resourceName: String): Certificate =
    CertificateFactory.getInstance("X.509").generateCertificate(resourceStream(resourceName))
}
