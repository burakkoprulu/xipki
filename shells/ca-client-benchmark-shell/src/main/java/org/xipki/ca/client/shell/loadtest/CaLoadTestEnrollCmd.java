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

package org.xipki.ca.client.shell.loadtest;

import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Completion;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.xipki.ca.client.shell.loadtest.KeyEntry.DSAKeyEntry;
import org.xipki.ca.client.shell.loadtest.KeyEntry.ECKeyEntry;
import org.xipki.ca.client.shell.loadtest.KeyEntry.RSAKeyEntry;
import org.xipki.ca.client.shell.loadtest.LoadTestEntry.RandomDn;
import org.xipki.ca.client.shell.loadtest.completer.RandomDnCompleter;
import org.xipki.common.util.StringUtil;
import org.xipki.console.karaf.IllegalCmdParamException;
import org.xipki.console.karaf.completer.ECCurveNameCompleter;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

@Command(scope = "xiqa", name = "cmp-benchmark-enroll",
        description = "CA client enroll load test")
@Service
public class CaLoadTestEnrollCmd extends CaLoadTestAction {

  @Option(name = "--profile", aliases = "-p", required = true,
      description =  "certificate profile that allows duplication of public key\n(required)")
  private String certprofile;

  @Option(name = "--subject", aliases = "-s", required = true,
          description = "subject template\n(required)")
  private String subjectTemplate;

  @Option(name = "--random-dn",
      description = "DN name to be incremented")
  @Completion(RandomDnCompleter.class)
  private String randomDnStr = "O";

  @Option(name = "--duration",
      description = "duration")
  private String duration = "30s";

  @Option(name = "--thread",
      description = "number of threads")
  private Integer numThreads = 5;

  @Option(name = "--key-type",
      description = "key type to be requested")
  private String keyType = "RSA";

  @Option(name = "--key-size",
      description = "modulus length of RSA key or p length of DSA key")
  private Integer keysize = 2048;

  @Option(name = "--curve",
          description = "EC curve name or OID of EC key")
  @Completion(ECCurveNameCompleter.class)
  private String curveName;

  @Option(name = "-n",
      description = "number of certificates to be requested in one request")
  private Integer num = 1;

  @Option(name = "--max-num",
      description = "maximal number of requests\n0 for unlimited")
  private Integer maxRequests = 0;

  @Override
  protected Object execute0() throws Exception {
    if (numThreads < 1) {
      throw new IllegalCmdParamException("invalid number of threads " + numThreads);
    }

    if ("EC".equalsIgnoreCase(keyType) && StringUtil.isBlank(curveName)) {
      throw new IllegalCmdParamException("curveName is not specified");
    }

    String description = StringUtil.concatObjectsCap(200, "subjectTemplate: ", subjectTemplate,
        "\nprofile: ", certprofile, "\nkeyType: ", keyType, "\nmaxRequests: ", maxRequests,
        "\nunit: ", num, " certificate", (num > 1 ? "s" : ""));

    RandomDn randomDn = null;
    if (randomDnStr != null) {
      randomDn = RandomDn.getInstance(randomDnStr);
      if (randomDn == null) {
        throw new IllegalCmdParamException("invalid randomDN " + randomDnStr);
      }
    }

    KeyEntry keyEntry;
    if ("EC".equalsIgnoreCase(keyType)) {
      keyEntry = new ECKeyEntry(curveName);
    } else if ("RSA".equalsIgnoreCase(keyType)) {
      keyEntry = new RSAKeyEntry(keysize.intValue());
    } else if ("DSA".equalsIgnoreCase(keyType)) {
      keyEntry = new DSAKeyEntry(keysize.intValue());
    } else {
      throw new IllegalCmdParamException("invalid keyType " + keyType);
    }

    LoadTestEntry loadtestEntry = new LoadTestEntry(certprofile, keyEntry, subjectTemplate,
        randomDn);
    CaLoadTestEnroll loadTest = new CaLoadTestEnroll(caClient, loadtestEntry, maxRequests, num,
        description);

    loadTest.setDuration(duration);
    loadTest.setThreads(numThreads);
    loadTest.test();

    return null;
  } // method execute0

}
