/*******************************************************************************
 * Copyright 2018 General Electric Company
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 *******************************************************************************/

package org.eclipse.keti.acceptance.test;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Map;

import org.eclipse.keti.acs.commons.web.AcsApiUriTemplates;
import org.eclipse.keti.acs.model.Attribute;
import org.eclipse.keti.acs.model.Effect;
import org.eclipse.keti.acs.rest.BaseResource;
import org.eclipse.keti.acs.rest.BaseSubject;
import org.eclipse.keti.acs.rest.PolicyEvaluationRequestV1;
import org.eclipse.keti.acs.rest.PolicyEvaluationResult;
import org.eclipse.keti.test.utils.ACSITSetUpFactory;
import org.eclipse.keti.test.utils.PolicyHelper;
import org.eclipse.keti.test.utils.PrivilegeHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2RestTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.testng.AbstractTestNGSpringContextTests;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * @author acs-engineers@ge.com
 */
@SuppressWarnings({ "nls" })
@ContextConfiguration("classpath:acceptance-test-spring-context.xml")
public class ACSAcceptanceIT extends AbstractTestNGSpringContextTests {

    private String acsBaseUrl;

    @Autowired
    private Environment environment;

    @Autowired
    private PrivilegeHelper privilegeHelper;

    @Autowired
    private PolicyHelper policyHelper;

    @Autowired
    private ACSITSetUpFactory acsitSetUpFactory;

    private OAuth2RestTemplate acsZoneRestTemplate;

    private HttpHeaders headersWithZoneSubdomain;

    private static final Logger LOGGER = LoggerFactory.getLogger(ACSAcceptanceIT.class);

    @BeforeClass
    public void setup() throws IOException {
        this.acsitSetUpFactory.setUp();
        this.headersWithZoneSubdomain = this.acsitSetUpFactory.getZone1Headers();
        this.acsZoneRestTemplate = this.acsitSetUpFactory.getAcsZoneAdminRestTemplate();
        this.acsBaseUrl = this.acsitSetUpFactory.getAcsUrl();

    }

    @SuppressWarnings("rawtypes")
    @Test
    public void testAcsHealth() {

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<String> heartbeatResponse = restTemplate.exchange(
                    this.acsBaseUrl + AcsApiUriTemplates.HEARTBEAT_URL, HttpMethod.GET,
                    new HttpEntity<>(this.headersWithZoneSubdomain), String.class);
            Assert.assertEquals(heartbeatResponse.getBody(), "alive", "ACS Heartbeat Check Failed");
        } catch (Exception e) {
            Assert.fail("Could not perform ACS Heartbeat Check: " + e.getMessage());
        }

        try {
            ResponseEntity<Map> healthStatus = restTemplate.exchange(this.acsBaseUrl + "/health", HttpMethod.GET,
                    new HttpEntity<>(this.headersWithZoneSubdomain), Map.class);
            Assert.assertNotNull(healthStatus);
            Assert.assertEquals(healthStatus.getBody().size(), 1);
            String acsStatus = (String) healthStatus.getBody().get("status");
            Assert.assertEquals(acsStatus, "UP", "ACS Health Check Failed: " + acsStatus);
        } catch (Exception e) {
            Assert.fail("Could not perform ACS Health Check: " + e.getMessage());
        }

    }

    @Test(dataProvider = "endpointProvider")
    public void testCompleteACSFlow(final String endpoint, final HttpHeaders headers,
            final PolicyEvaluationRequestV1 policyEvalRequest, final Effect expectedEffect) throws Exception {

        String testPolicyName = null;
        BaseSubject bob = null;
        BaseResource alarms = null;
        try {
            LOGGER.info("Adding a policy 'Subjects can access resource if they are assigned to the same site'.");
            testPolicyName = this.policyHelper.setTestPolicy(this.acsZoneRestTemplate, headers, endpoint,
                    "src/test/resources/testCompleteACSFlow.json");
            BaseSubject subject = new BaseSubject(policyEvalRequest.getSubjectIdentifier());
            Attribute site = new Attribute();
            site.setIssuer("issuerId1");
            site.setName("site");
            site.setValue("sanramon");

            LOGGER.info("Adding a subject '{}' assigned to a site '{}'.", subject.getSubjectIdentifier(),
                    site.getValue());
            bob = this.privilegeHelper.putSubject(this.acsZoneRestTemplate, subject, endpoint, headers, site);

            BaseResource resource = new BaseResource();
            resource.setResourceIdentifier(policyEvalRequest.getResourceIdentifier());

            LOGGER.info("Adding a resource '{}'.", resource.getResourceIdentifier());
            alarms = this.privilegeHelper.putResource(this.acsZoneRestTemplate, resource, endpoint, headers,
                    new Attribute());

            LOGGER.info("Evaluating if subject '{}' has access to resource '{}'.", bob.getSubjectIdentifier(),
                    alarms.getResourceIdentifier());
            ResponseEntity<PolicyEvaluationResult> evalResponse = this.acsZoneRestTemplate.postForEntity(
                    endpoint + PolicyHelper.ACS_POLICY_EVAL_API_PATH, new HttpEntity<>(policyEvalRequest, headers),
                    PolicyEvaluationResult.class);

            Assert.assertEquals(evalResponse.getStatusCode(), HttpStatus.OK);
            PolicyEvaluationResult responseBody = evalResponse.getBody();
            LOGGER.info("Request for subject '{}' assigned to '{}' to access resource '{}' returned '{}'.",
                    bob.getSubjectIdentifier(), site.getValue(), alarms.getResourceIdentifier(),
                    responseBody.getEffect().toString());
            Assert.assertEquals(responseBody.getEffect(), expectedEffect);
        } finally {
            // delete policy
            if (null != testPolicyName) {
                this.acsZoneRestTemplate.exchange(endpoint + PolicyHelper.ACS_POLICY_SET_API_PATH + testPolicyName,
                        HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
            }

            // delete attributes
            if (null != bob) {
                this.acsZoneRestTemplate.exchange(
                        endpoint + PrivilegeHelper.ACS_SUBJECT_API_PATH + bob.getSubjectIdentifier(),
                        HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
            }
            if (null != alarms) {
                String encodedResource = URLEncoder.encode(alarms.getResourceIdentifier(), "UTF-8");
                URI uri = new URI(endpoint + PrivilegeHelper.ACS_RESOURCE_API_PATH + encodedResource);
                this.acsZoneRestTemplate.exchange(uri, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
            }
        }
    }

    @DataProvider(name = "endpointProvider")
    public Object[][] getAcsEndpoint() throws Exception {
        PolicyEvaluationRequestV1 policyEvalForBobPermit = this.policyHelper.createEvalRequest("GET", "bob",
                "/alarms/sites/sanramon", null);
        PolicyEvaluationRequestV1 policyEvalForBobDeny = this.policyHelper.createEvalRequest("GET", "bob",
                "/alarms/sites/newyork", null);
        return new Object[][] {
                { this.acsBaseUrl, this.headersWithZoneSubdomain, policyEvalForBobPermit, Effect.PERMIT },
                { this.acsBaseUrl, this.headersWithZoneSubdomain, policyEvalForBobDeny, Effect.DENY } };
    }

    private ResponseEntity<String> getMonitoringApiResponse(final HttpHeaders headers) {
        return new RestTemplate().exchange(URI.create(this.acsBaseUrl + AcsApiUriTemplates.HEARTBEAT_URL),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    // TODO: Remove this test when the "httpValidation" Spring profile is removed
    @Test
    public void testHttpValidationBasedOnActiveSpringProfile() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE);

        if (!Arrays.asList(this.environment.getActiveProfiles()).contains("httpValidation")) {
            Assert.assertEquals(this.getMonitoringApiResponse(headers).getStatusCode(), HttpStatus.OK);
            return;
        }

        try {
            this.getMonitoringApiResponse(headers);
            Assert.fail("Expected an HttpMediaTypeNotAcceptableException exception to be thrown");
        } catch (final HttpClientErrorException e) {
            Assert.assertEquals(e.getStatusCode(), HttpStatus.NOT_ACCEPTABLE);
        }
    }

    @AfterClass
    public void tearDown() {
        this.acsitSetUpFactory.destroy();
    }

}
