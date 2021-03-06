/*
 *
 * Copyright (c) 2013 - 2018 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.security.speed.p12;

import org.xipki.security.SecurityFactory;
import org.xipki.security.pkcs12.KeystoreGenerationParameters;
import org.xipki.security.pkcs12.P12KeyGenerationResult;
import org.xipki.security.pkcs12.P12KeyGenerator;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.2.0
 */
// CHECKSTYLE:SKIP
public class P12AESGmacSignLoadTest extends P12SignLoadTest {

  public P12AESGmacSignLoadTest(SecurityFactory securityFactory, String signatureAlgorithm)
      throws Exception {
    super("JCEKS", securityFactory, signatureAlgorithm, generateKeystore(signatureAlgorithm),
        "JCEKS AES-GMAC signature creation");
  }

  private static byte[] generateKeystore(String signatureAlgorithm) throws Exception {
    int keysize = getKeysize(signatureAlgorithm);
    P12KeyGenerationResult identity = new P12KeyGenerator().generateSecretKey(
        "AES", keysize, new KeystoreGenerationParameters(PASSWORD.toCharArray()));
    return identity.keystore();
  }

  public static int getKeysize(String hmacAlgorithm) {
    int keysize;
    if ("AES128-GMAC".equalsIgnoreCase(hmacAlgorithm)) {
      keysize = 128;
    } else if ("AES192-GMAC".equalsIgnoreCase(hmacAlgorithm)) {
      keysize = 192;
    } else if ("AES256-GMAC".equalsIgnoreCase(hmacAlgorithm)) {
      keysize = 256;
    } else {
      throw new IllegalArgumentException("unknown HMAC algorithm " + hmacAlgorithm);
    }
    return keysize;
  }

}
