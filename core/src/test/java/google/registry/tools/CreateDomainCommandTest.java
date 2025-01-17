// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.joda.money.CurrencyUnit.JPY;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableSet;
import google.registry.dns.writer.VoidDnsWriter;
import google.registry.model.pricing.StaticPremiumListPricingEngine;
import google.registry.model.tld.Registry;
import google.registry.model.tld.label.PremiumListDao;
import google.registry.testing.DeterministicStringGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CreateDomainCommand}. */
class CreateDomainCommandTest extends EppToolCommandTestCase<CreateDomainCommand> {

  @BeforeEach
  void beforeEach() {
    command.passwordGenerator = new DeterministicStringGenerator("abcdefghijklmnopqrstuvwxyz");
  }

  @Test
  void testSuccess_complete() throws Exception {
    runCommandForced(
        "--client=NewRegistrar",
        "--period=1",
        "--nameservers=ns1.zdns.google,ns2.zdns.google,ns3.zdns.google,ns4.zdns.google",
        "--registrant=crr-admin",
        "--admins=crr-admin",
        "--techs=crr-tech",
        "--password=2fooBAR",
        "--ds_records=1 2 3 abcd,4 5 6 EF01",
        "--ds_records=60485 5  2  D4B7D520E7BB5F0F67674A0CCEB1E3E0614B93C4F9E99B8383F6A1E4469DA50A",
        "example.tld");
    eppVerifier.verifySent("domain_create_complete.xml");
  }

  @Test
  void testSuccess_completeWithSquareBrackets() throws Exception {
    runCommandForced(
        "--client=NewRegistrar",
        "--period=1",
        "--nameservers=ns[1-4].zdns.google",
        "--registrant=crr-admin",
        "--admins=crr-admin",
        "--techs=crr-tech",
        "--password=2fooBAR",
        "--ds_records=1 2 3 abcd,4 5 6 EF01",
        "--ds_records=60485 5  2  D4B7D520E7BB5F0F67674A0CCEB1E3E0614B93C4F9E99B8383F6A1E4469DA50A",
        "example.tld");
    eppVerifier.verifySent("domain_create_complete.xml");
  }

  @Test
  void testSuccess_minimal() throws Exception {
    // Test that each optional field can be omitted. Also tests the auto-gen password.
    runCommandForced(
        "--client=NewRegistrar",
        "--registrant=crr-admin",
        "--admins=crr-admin",
        "--techs=crr-tech",
        "example.tld");
    eppVerifier.verifySent("domain_create_minimal.xml");
  }

  @Test
  void testSuccess_multipleDomains() throws Exception {
    createTld("abc");
    runCommandForced(
        "--client=NewRegistrar",
        "--registrant=crr-admin",
        "--admins=crr-admin",
        "--techs=crr-tech",
        "example.tld",
        "example.abc");
    eppVerifier.verifySent("domain_create_minimal.xml").verifySent("domain_create_minimal_abc.xml");
  }

  @Test
  void testSuccess_premiumListNull() throws Exception {
    Registry registry =
        new Registry.Builder()
            .setTldStr("abc")
            .setPremiumPricingEngine(StaticPremiumListPricingEngine.NAME)
            .setDnsWriters(ImmutableSet.of(VoidDnsWriter.NAME))
            .setPremiumList(null)
            .build();
    tm().transact(() -> tm().put(registry));
    runCommandForced(
        "--client=NewRegistrar",
        "--registrant=crr-admin",
        "--admins=crr-admin",
        "--techs=crr-tech",
        "example.tld",
        "example.abc");
    eppVerifier
        .verifySent("domain_create_minimal.xml")
        .verifySent("domain_create_minimal_abc.xml");
  }

  @Test
  void testSuccess_premiumJpyDomain() throws Exception {
    createTld("baar");
    persistPremiumList("baar", JPY, "parajiumu,JPY 96083");
    persistResource(
        Registry.get("baar")
            .asBuilder()
            .setPremiumList(PremiumListDao.getLatestRevision("baar").get())
            .build());
    runCommandForced(
        "--client=NewRegistrar",
        "--registrant=crr-admin",
        "--admins=crr-admin",
        "--techs=crr-tech",
        "--period=3",
        "--force_premiums",
        "parajiumu.baar");
    eppVerifier.verifySent("domain_create_parajiumu_3yrs.xml");
    assertInStdout(
        "parajiumu.baar is premium at JPY 96083 per year; "
            + "sending total cost for 3 year(s) of JPY 288249.");
  }

  @Test
  void testSuccess_multipleDomainsWithPremium() throws Exception {
    createTld("abc");
    runCommandForced(
        "--client=NewRegistrar",
        "--registrant=crr-admin",
        "--admins=crr-admin",
        "--techs=crr-tech",
        "--force_premiums",
        "example.tld",
        "palladium.tld",
        "example.abc");
    eppVerifier
        .verifySent("domain_create_minimal.xml")
        .verifySent("domain_create_palladium.xml")
        .verifySent("domain_create_minimal_abc.xml");
    assertInStdout(
        "palladium.tld is premium at USD 877.00 per year; "
            + "sending total cost for 1 year(s) of USD 877.00.");
  }

  @Test
  void testSuccess_reasonAndRegistrarRequest() throws Exception {
    createTld("tld");
    runCommandForced(
        "--client=NewRegistrar",
        "--registrant=crr-admin",
        "--admins=crr-admin",
        "--techs=crr-tech",
        "--reason=\"Creating test domain\"",
        "--registrar_request=false",
        "example.tld");
    eppVerifier.verifySent("domain_create_metadata.xml");
  }

  @Test
  void testFailure_duplicateDomains() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--admins=crr-admin",
                    "--techs=crr-tech",
                    "example.tld",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("Duplicate arguments found: 'example.tld'");
  }

  @Test
  void testFailure_missingDomain() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--admins=crr-admin",
                    "--techs=crr-tech"));
    assertThat(thrown).hasMessageThat().contains("Main parameters are required");
  }

  @Test
  void testFailure_missingClientId() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    "--admins=crr-admin",
                    "--techs=crr-tech",
                    "--registrant=crr-admin",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("--client");
  }

  @Test
  void testFailure_missingRegistrant() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--admins=crr-admin",
                    "--techs=crr-tech",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("Registrant must be specified");
  }

  @Test
  void testFailure_missingAdmins() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--techs=crr-tech",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("At least one admin must be specified");
  }

  @Test
  void testFailure_missingTechs() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--admins=crr-admin",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("At least one tech must be specified");
  }

  @Test
  void testFailure_tooManyNameServers() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--admins=crr-admin",
                    "--techs=crr-tech",
                    "--nameservers=ns1.zdns.google,ns2.zdns.google,ns3.zdns.google,ns4.zdns.google,"
                        + "ns5.zdns.google,ns6.zdns.google,ns7.zdns.google,ns8.zdns.google,"
                        + "ns9.zdns.google,ns10.zdns.google,ns11.zdns.google,ns12.zdns.google,"
                        + "ns13.zdns.google,ns14.zdns.google",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("There can be at most 13 nameservers");
  }

  @Test
  void testFailure_tooManyNameServers_usingSquareBracketRange() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--admins=crr-admin",
                    "--techs=crr-tech",
                    "--nameservers=ns[1-14].zdns.google",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("There can be at most 13 nameservers");
  }

  @Test
  void testFailure_badPeriod() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--admins=crr-admin",
                    "--techs=crr-tech",
                    "--period=x",
                    "--domain=example.tld"));
    assertThat(thrown).hasMessageThat().contains("--period");
  }

  @Test
  void testFailure_dsRecordsNot4Parts() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--admins=crr-admin",
                    "--techs=crr-tech",
                    "--ds_records=1 2 3 ab cd",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("should have 4 parts, but has 5");
  }

  @Test
  void testFailure_keyTagNotNumber() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--admins=crr-admin",
                    "--techs=crr-tech",
                    "--ds_records=x 2 3 abcd",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("\"x\"");
  }

  @Test
  void testFailure_algNotNumber() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--admins=crr-admin",
                    "--techs=crr-tech",
                    "--ds_records=1 x 3 abcd",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("\"x\"");
  }

  @Test
  void testFailure_digestTypeNotNumber() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--admins=crr-admin",
                    "--techs=crr-tech",
                    "--ds_records=1 2 x abcd",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("\"x\"");
  }

  @Test
  void testFailure_digestNotHex() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--admins=crr-admin",
                    "--techs=crr-tech",
                    "--ds_records=1 2 3 xbcd",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("XBCD");
  }

  @Test
  void testFailure_digestNotEvenLengthed() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--admins=crr-admin",
                    "--techs=crr-tech",
                    "--ds_records=1 2 3 abcde",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("length 5");
  }

  @Test
  void testFailure_cantForceCreatePremiumDomain_withoutForcePremiums() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--admins=crr-admin",
                    "--techs=crr-tech",
                    "gold.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Forced creates on premium domain(s) require --force_premiums");
  }
}
