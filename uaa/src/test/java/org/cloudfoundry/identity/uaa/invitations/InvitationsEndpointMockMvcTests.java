package org.cloudfoundry.identity.uaa.invitations;

import com.fasterxml.jackson.core.type.TypeReference;
import org.cloudfoundry.identity.uaa.codestore.ExpiringCode;
import org.cloudfoundry.identity.uaa.codestore.ExpiringCodeStore;
import org.cloudfoundry.identity.uaa.codestore.ExpiringCodeType;
import org.cloudfoundry.identity.uaa.constants.OriginKeys;
import org.cloudfoundry.identity.uaa.mock.InjectedMockContextTest;
import org.cloudfoundry.identity.uaa.mock.util.MockMvcUtils;
import org.cloudfoundry.identity.uaa.provider.IdentityProvider;
import org.cloudfoundry.identity.uaa.provider.JdbcIdentityProviderProvisioning;
import org.cloudfoundry.identity.uaa.provider.UaaIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.scim.ScimUser;
import org.cloudfoundry.identity.uaa.zone.BrandingInformation;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneConfiguration;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneHolder;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneProvisioning;
import org.cloudfoundry.identity.uaa.zone.MultitenantJdbcClientDetailsService;
import org.flywaydb.core.internal.util.StringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.common.util.OAuth2Utils;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;
import org.springframework.security.oauth2.provider.ClientDetails;
import org.springframework.security.oauth2.provider.client.BaseClientDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.apache.commons.lang3.StringUtils.contains;
import static org.cloudfoundry.identity.uaa.constants.OriginKeys.ORIGIN;
import static org.cloudfoundry.identity.uaa.constants.OriginKeys.UAA;

import static org.cloudfoundry.identity.uaa.util.JsonUtils.readValue;
import static org.cloudfoundry.identity.uaa.util.JsonUtils.writeValueAsString;
import static org.cloudfoundry.identity.uaa.zone.IdentityZoneSwitchingFilter.HEADER;
import static org.cloudfoundry.identity.uaa.zone.IdentityZoneSwitchingFilter.SUBDOMAIN_HEADER;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.oauth2.common.util.OAuth2Utils.CLIENT_ID;
import static org.springframework.security.oauth2.common.util.OAuth2Utils.REDIRECT_URI;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class InvitationsEndpointMockMvcTests extends InjectedMockContextTest {

    private String scimInviteToken;
    private RandomValueStringGenerator generator;
    private String clientId;
    private String clientSecret;
    private ClientDetails clientDetails;
    private String adminToken;
    private String authorities;
    private String domain;
    private ExpiringCodeStore codeStore;

    @Before
    public void setUp() throws Exception {
        generator = new RandomValueStringGenerator();
        adminToken = MockMvcUtils.getClientCredentialsOAuthAccessToken(getMockMvc(), "admin", "adminsecret", "clients.read clients.write clients.secret scim.read scim.write clients.admin uaa.admin", null);
        clientId = generator.generate().toLowerCase();
        clientSecret = generator.generate().toLowerCase();
        authorities = "scim.read,scim.invite";
        clientDetails = MockMvcUtils.createClient(this.getMockMvc(), adminToken, clientId, clientSecret, Collections.singleton("oauth"), Arrays.asList("scim.read", "scim.invite"), Arrays.asList("client_credentials", "password"), authorities);
        scimInviteToken = MockMvcUtils.getClientCredentialsOAuthAccessToken(getMockMvc(), clientId, clientSecret, "scim.read scim.invite", null);
        domain = generator.generate().toLowerCase() + ".com";
        IdentityProvider uaaProvider = getWebApplicationContext().getBean(JdbcIdentityProviderProvisioning.class).retrieveByOrigin(UAA, IdentityZone.getUaa().getId());
        if (uaaProvider.getConfig() == null) {
            uaaProvider.setConfig(new UaaIdentityProviderDefinition(null, null));
        }
        uaaProvider.getConfig().setEmailDomain(Arrays.asList(domain, "example.com"));
        getWebApplicationContext().getBean(JdbcIdentityProviderProvisioning.class).update(uaaProvider, uaaProvider.getIdentityZoneId());
        codeStore = getWebApplicationContext().getBean(ExpiringCodeStore.class);
    }

    @After
    public void cleanUpDomainList() {
        IdentityProvider uaaProvider = getWebApplicationContext().getBean(JdbcIdentityProviderProvisioning.class).retrieveByOrigin(UAA, IdentityZone.getUaa().getId());
        uaaProvider.getConfig().setEmailDomain(null);
        getWebApplicationContext().getBean(JdbcIdentityProviderProvisioning.class).update(uaaProvider, uaaProvider.getIdentityZoneId());
    }

    @Test
    public void inviteUserWithClientCredentials() throws Exception {
        String email = "user1@example.com";
        String redirectUrl = "example.com";
        InvitationsResponse response = sendRequestWithTokenAndReturnResponse(getMockMvc(), scimInviteToken, null, clientId, redirectUrl, email);
        assertResponseAndCodeCorrect(codeStore, new String[]{email}, redirectUrl, null, response, clientDetails);
    }

    @Test
    public void inviteMultipleUsersWithClientCredentials() throws Exception {
        String[] emails = new String[]{"user1@" + domain, "user2@" + domain};
        String redirectUri = "example.com";
        InvitationsResponse response = sendRequestWithTokenAndReturnResponse(getMockMvc(), scimInviteToken, null, clientId, redirectUri, emails);
        assertResponseAndCodeCorrect(codeStore, emails, redirectUri, null, response, clientDetails);
    }

    @Test
    public void inviteUserWithUserCredentials() throws Exception {
        String email = "user1@example.com";
        String redirectUri = "example.com";
        String userToken = MockMvcUtils.getScimInviteUserToken(getMockMvc(), clientId, clientSecret, null);
        InvitationsResponse response = sendRequestWithTokenAndReturnResponse(getMockMvc(), userToken, null, clientId, redirectUri, email);
        assertResponseAndCodeCorrect(codeStore, new String[]{email}, redirectUri, null, response, clientDetails);
    }

    @Test
    public void inviteUserInZoneWithDefaultZoneUaaAdmin() throws Exception {
        String subdomain = generator.generate();
        MockMvcUtils.IdentityZoneCreationResult result = MockMvcUtils.createOtherIdentityZoneAndReturnResult(subdomain, getMockMvc(), getWebApplicationContext(), null);

        String email = "user1@example.com";
        String redirectUrl = "example.com";

        InvitationsRequest invitations = new InvitationsRequest(new String[]{email});

        String requestBody = writeValueAsString(invitations);

        MockHttpServletRequestBuilder post = post("/invite_users")
                .param(OAuth2Utils.REDIRECT_URI, redirectUrl)
                .header("Authorization", "Bearer " + adminToken)
                .header(SUBDOMAIN_HEADER, result.getIdentityZone().getSubdomain())
                .contentType(APPLICATION_JSON)
                .content(requestBody);

        MvcResult mvcResult = getMockMvc().perform(post)
                .andExpect(status().isOk())
                .andReturn();

        InvitationsResponse invitationsResponse = readValue(mvcResult.getResponse().getContentAsString(), InvitationsResponse.class);
        BaseClientDetails defaultClientDetails = new BaseClientDetails();
        defaultClientDetails.setClientId("admin");
        assertResponseAndCodeCorrect(codeStore, new String[]{email}, redirectUrl, result.getIdentityZone(), invitationsResponse, defaultClientDetails);

    }

    @Test
    public void inviteUserInZoneWithDefaultZoneZoneAdmin() throws Exception {
        String subdomain = generator.generate();
        MockMvcUtils.IdentityZoneCreationResult result = MockMvcUtils.createOtherIdentityZoneAndReturnResult(subdomain, getMockMvc(), getWebApplicationContext(), null);

        String zonifiedAdminClientId = generator.generate().toLowerCase();
        String zonifiedAdminClientSecret = generator.generate().toLowerCase();

        ClientDetails zonifiedScimInviteClientDetails = MockMvcUtils.createClient(this.getMockMvc(), adminToken, zonifiedAdminClientId, zonifiedAdminClientSecret, Collections.singleton("oauth"), null, Arrays.asList("client_credentials", "password"), "zones." + result.getIdentityZone().getId() + ".admin");
        String zonifiedScimInviteToken = MockMvcUtils.getClientCredentialsOAuthAccessToken(getMockMvc(), zonifiedAdminClientId, zonifiedAdminClientSecret, "zones." + result.getIdentityZone().getId() + ".admin", null);

        String email = "user1@example.com";
        String redirectUrl = "example.com";

        InvitationsRequest invitations = new InvitationsRequest(new String[]{email});

        String requestBody = writeValueAsString(invitations);

        MockHttpServletRequestBuilder post = post("/invite_users")
                .param(OAuth2Utils.REDIRECT_URI, redirectUrl)
                .header("Authorization", "Bearer " + zonifiedScimInviteToken)
                .header(SUBDOMAIN_HEADER, result.getIdentityZone().getSubdomain())
                .contentType(APPLICATION_JSON)
                .content(requestBody);

        MvcResult mvcResult = getMockMvc().perform(post)
                .andExpect(status().isOk())
                .andReturn();

        InvitationsResponse invitationsResponse = readValue(mvcResult.getResponse().getContentAsString(), InvitationsResponse.class);
        assertResponseAndCodeCorrect(codeStore, new String[]{email}, redirectUrl, result.getIdentityZone(), invitationsResponse, zonifiedScimInviteClientDetails);

    }

    @Test
    public void inviteUserInZoneWithDefaultZoneScimInvite() throws Exception {
        String subdomain = generator.generate();
        MockMvcUtils.IdentityZoneCreationResult result = MockMvcUtils.createOtherIdentityZoneAndReturnResult(subdomain, getMockMvc(), getWebApplicationContext(), null);

        String zonifiedScimInviteClientId = generator.generate().toLowerCase();
        String zonifiedScimInviteClientSecret = generator.generate().toLowerCase();

        ClientDetails zonifiedScimInviteClientDetails = MockMvcUtils.createClient(this.getMockMvc(), adminToken, zonifiedScimInviteClientId, zonifiedScimInviteClientSecret, Collections.singleton("oauth"), null, Arrays.asList("client_credentials", "password"), "zones." + result.getIdentityZone().getId() + ".scim.invite");
        String zonifiedScimInviteToken = MockMvcUtils.getClientCredentialsOAuthAccessToken(getMockMvc(), zonifiedScimInviteClientId, zonifiedScimInviteClientSecret, "zones." + result.getIdentityZone().getId() + ".scim.invite", null);

        String email = "user1@example.com";
        String redirectUrl = "example.com";

        InvitationsRequest invitations = new InvitationsRequest(new String[]{email});

        String requestBody = writeValueAsString(invitations);

        MockHttpServletRequestBuilder post = post("/invite_users")
                .param(OAuth2Utils.REDIRECT_URI, redirectUrl)
                .header("Authorization", "Bearer " + zonifiedScimInviteToken)
                .header(HEADER, result.getIdentityZone().getId())
                .contentType(APPLICATION_JSON)
                .content(requestBody);

        MvcResult mvcResult = getMockMvc().perform(post)
                .andExpect(status().isOk())
                .andReturn();

        InvitationsResponse invitationsResponse = readValue(mvcResult.getResponse().getContentAsString(), InvitationsResponse.class);
        assertResponseAndCodeCorrect(codeStore, new String[]{email}, redirectUrl, result.getIdentityZone(), invitationsResponse, zonifiedScimInviteClientDetails);

    }

    @Test
    public void inviteUserWithinZone() throws Exception {
        String subdomain = generator.generate().toLowerCase();
        MockMvcUtils.IdentityZoneCreationResult result = MockMvcUtils.createOtherIdentityZoneAndReturnResult(subdomain, getMockMvc(), getWebApplicationContext(), null);

        String zonedClientId = "zonedClientId";
        String zonedClientSecret = "zonedClientSecret";
        BaseClientDetails zonedClientDetails = (BaseClientDetails) MockMvcUtils.createClient(this.getMockMvc(), result.getZoneAdminToken(), zonedClientId, zonedClientSecret, Collections.singleton("oauth"), Arrays.asList("scim.read", "scim.invite"), Arrays.asList("client_credentials", "password"), authorities, Collections.singleton("http://redirect.uri"), result.getIdentityZone());
        zonedClientDetails.setClientSecret(zonedClientSecret);
        String zonedScimInviteToken = MockMvcUtils.getClientCredentialsOAuthAccessToken(getMockMvc(), zonedClientDetails.getClientId(), zonedClientDetails.getClientSecret(), "scim.read scim.invite", subdomain);

        String email = "user1@example.com";
        String redirectUrl = "example.com";
        InvitationsResponse response = sendRequestWithTokenAndReturnResponse(getMockMvc(), zonedScimInviteToken, result.getIdentityZone().getSubdomain(), zonedClientDetails.getClientId(), redirectUrl, email);

        assertResponseAndCodeCorrect(codeStore, new String[]{email}, redirectUrl, result.getIdentityZone(), response, zonedClientDetails);
    }

    @Test
    public void multipleUsersEmailExistsWithOneOrigin() throws Exception {
        String clientAdminToken = MockMvcUtils.getClientOAuthAccessToken(getMockMvc(), "admin", "adminsecret", "");
        String username1 = generator.generate();
        String username2 = generator.generate();
        String email = generator.generate().toLowerCase() + "@" + domain;
        ScimUser user1 = new ScimUser(null, username1, "givenName", "familyName");
        user1.setPrimaryEmail(email);
        user1.setOrigin(UAA);
        user1.setPassword("password");
        MockMvcUtils.createUser(getMockMvc(), clientAdminToken, user1);
        ScimUser user2 = new ScimUser(null, username2, "givenName", "familyName");
        user2.setPrimaryEmail(email);
        user2.setOrigin(UAA);
        user2.setPassword("password");
        MockMvcUtils.createUser(getMockMvc(), clientAdminToken, user2);

        String userToken = MockMvcUtils.getScimInviteUserToken(getMockMvc(), clientId, clientSecret, null);
        InvitationsResponse response = sendRequestWithTokenAndReturnResponse(getMockMvc(), userToken, null, clientId, "example.com", email);
        assertEquals(0, response.getNewInvites().size());
        assertEquals(1, response.getFailedInvites().size());
        assertEquals("user.ambiguous", response.getFailedInvites().get(0).getErrorCode());
    }

    @Test
    public void inviteUserWithInvalidEmails() throws Exception {
        String invalidEmail1 = "user1example.";
        String invalidEmail2 = "user1example@";
        String invalidEmail3 = "user1example@invalid";
        String redirectUrl = "test.com";
        InvitationsResponse response = sendRequestWithTokenAndReturnResponse(getMockMvc(), scimInviteToken, null, clientId, redirectUrl, invalidEmail1, invalidEmail2, invalidEmail3);
        assertEquals(0, response.getNewInvites().size());
        assertEquals(3, response.getFailedInvites().size());

        assertEquals("email.invalid", response.getFailedInvites().get(0).getErrorCode());
        assertEquals("email.invalid", response.getFailedInvites().get(1).getErrorCode());
        assertEquals("email.invalid", response.getFailedInvites().get(2).getErrorCode());
        assertEquals(invalidEmail1 + " is invalid email.", response.getFailedInvites().get(0).getErrorMessage());
        assertEquals(invalidEmail2 + " is invalid email.", response.getFailedInvites().get(1).getErrorMessage());
        assertEquals(invalidEmail3 + " is invalid email.", response.getFailedInvites().get(2).getErrorMessage());
    }

    @Test
    public void acceptInvitationEmailWithDefaultCompanyName() throws Exception {
        getMockMvc().perform(get(getAcceptInvitationLink(getMockMvc(), clientId, clientSecret, generator, domain, null)))
                .andExpect(content().string(containsString("Create your account")))
                .andExpect(content().string(containsString("Create account")));
    }

    @Test
    public void acceptInvitationEmailWithCompanyName() throws Exception {
        IdentityZoneConfiguration defaultConfig = IdentityZoneHolder.get().getConfig();
        BrandingInformation branding = new BrandingInformation();
        branding.setCompanyName("Best Company");
        IdentityZoneConfiguration config = new IdentityZoneConfiguration();
        config.setBranding(branding);
        IdentityZone defaultZone = IdentityZoneHolder.getUaaZone();
        defaultZone.setConfig(config);
        getWebApplicationContext().getBean(IdentityZoneProvisioning.class).update(defaultZone);
        try {
            getMockMvc().perform(get(getAcceptInvitationLink(getMockMvc(), clientId, clientSecret, generator, domain, null)))
                    .andExpect(content().string(containsString("Create your Best Company account")))
                    .andExpect(content().string(containsString("Create Best Company account")))
                    .andExpect(content().string(not(containsString("Create account"))));
        } finally {
            defaultZone.setConfig(defaultConfig);
            getWebApplicationContext().getBean(IdentityZoneProvisioning.class).update(defaultZone);
        }
    }

    @Test
    public void acceptInvitationEmailWithinZone() throws Exception {
        String subdomain = generator.generate();
        IdentityZone zone = MockMvcUtils.createOtherIdentityZone(subdomain, getMockMvc(), getWebApplicationContext());

        BrandingInformation branding = new BrandingInformation();
        branding.setCompanyName("Best Company");
        IdentityZoneConfiguration config = new IdentityZoneConfiguration();
        config.setBranding(branding);
        zone.setConfig(config);

        getWebApplicationContext().getBean(IdentityZoneProvisioning.class).update(zone);

        BaseClientDetails client = MockMvcUtils.getClientDetailsModification(clientId, clientSecret, Collections.singleton("oauth"), Arrays.asList("scim.read", "scim.invite"), Arrays.asList("client_credentials", "password"), authorities, Collections.singleton("http://redirect.uri"));
        IdentityZone original = IdentityZoneHolder.get();
        try {
            IdentityZoneHolder.set(zone);
            getWebApplicationContext().getBean(MultitenantJdbcClientDetailsService.class).addClientDetails(client);
        } finally {
            IdentityZoneHolder.set(original);
        }
        String acceptInvitationLink = getAcceptInvitationLink(getMockMvc(), clientId, clientSecret, generator, domain, zone);

        getMockMvc().perform(get(acceptInvitationLink)
                .header("Host", (subdomain + ".localhost")))
                .andExpect(content().string(containsString("Create your account")))
                .andExpect(content().string(containsString("Best Company")))
                .andExpect(content().string(containsString("Create account")));
    }

    @Test
    public void invitationsAcceptGetSecurity() throws Exception {
        getWebApplicationContext().getBean(JdbcTemplate.class).update("DELETE FROM expiring_code_store");

        String userToken = MockMvcUtils.getScimInviteUserToken(getMockMvc(), clientId, clientSecret, null);
        sendRequestWithToken(getMockMvc(), userToken, clientId, "user1@" + domain);

        String code = getWebApplicationContext().getBean(JdbcTemplate.class).queryForObject("SELECT code FROM expiring_code_store", String.class);
        assertNotNull("Invite Code Must be Present", code);

        MockHttpServletRequestBuilder accept = get("/invitations/accept")
                .param("code", code);

        getMockMvc().perform(accept)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<form action=\"/invitations/accept.do\" method=\"post\" novalidate=\"novalidate\">")));
    }

    private static InvitationsResponse sendRequestWithTokenAndReturnResponse(MockMvc mockMvc,
                                                                             String token,
                                                                             String subdomain,
                                                                             String clientId,
                                                                             String redirectUri,
                                                                             String... emails) throws Exception {
        return MockMvcUtils.sendRequestWithTokenAndReturnResponse(getWebApplicationContext(),
                mockMvc, token, subdomain, clientId, redirectUri, emails);
    }

    private static void sendRequestWithToken(MockMvc mockMvc, String token, String clientId, String... emails) throws Exception {
        InvitationsResponse response = sendRequestWithTokenAndReturnResponse(mockMvc, token, null, clientId, "example.com", emails);
        assertThat(response.getNewInvites().size(), is(emails.length));
        assertThat(response.getFailedInvites().size(), is(0));
    }

    private static void assertResponseAndCodeCorrect(ExpiringCodeStore codeStore, String[] emails, String redirectUrl, IdentityZone zone, InvitationsResponse response, ClientDetails clientDetails) {
        for (int i = 0; i < emails.length; i++) {
            assertThat(response.getNewInvites().size(), is(emails.length));
            assertThat(response.getNewInvites().get(i).getEmail(), is(emails[i]));
            assertThat(response.getNewInvites().get(i).getOrigin(), is(OriginKeys.UAA));
            assertThat(response.getNewInvites().get(i).getUserId(), is(notNullValue()));
            assertThat(response.getNewInvites().get(i).getErrorCode(), is(nullValue()));
            assertThat(response.getNewInvites().get(i).getErrorMessage(), is(nullValue()));
            String link = response.getNewInvites().get(i).getInviteLink().toString();
            assertFalse(contains(link, "@"));
            assertFalse(contains(link, "%40"));
            if (zone != null && StringUtils.hasText(zone.getSubdomain())) {
                assertThat(link, startsWith("http://" + zone.getSubdomain() + ".localhost/invitations/accept"));
                IdentityZoneHolder.set(zone);
            } else {
                assertThat(link, startsWith("http://localhost/invitations/accept"));
            }

            String query = response.getNewInvites().get(i).getInviteLink().getQuery();
            assertThat(query, startsWith("code="));
            String code = query.split("=")[1];

            ExpiringCode expiringCode = codeStore.retrieveCode(code, IdentityZoneHolder.get().getId());
            IdentityZoneHolder.clear();
            assertThat(expiringCode.getExpiresAt().getTime(), is(greaterThan(System.currentTimeMillis())));
            assertThat(expiringCode.getIntent(), is(ExpiringCodeType.INVITATION.name()));
            Map<String, String> data = readValue(expiringCode.getData(), new TypeReference<Map<String, String>>() {
            });
            assertThat(data, is(not(nullValue())));
            assertThat(data.get(InvitationConstants.USER_ID), is(notNullValue()));
            assertThat(data.get(InvitationConstants.EMAIL), is(emails[i]));
            assertThat(data.get(ORIGIN), is(OriginKeys.UAA));
            assertThat(data.get(CLIENT_ID), is(clientDetails.getClientId()));
            assertThat(data.get(REDIRECT_URI), is(redirectUrl));
        }
    }

    private static String getAcceptInvitationLink(MockMvc mockMvc,String clientId, String clientSecret, RandomValueStringGenerator generator, String domain, IdentityZone zone) throws Exception {
        String userToken = MockMvcUtils.getScimInviteUserToken(mockMvc, clientId, clientSecret, zone);
        String email = generator.generate().toLowerCase() + "@" + domain;
        InvitationsResponse response = sendRequestWithTokenAndReturnResponse(mockMvc, userToken, zone == null ? null : zone.getSubdomain(), clientId, "example.com", email);
        return response.getNewInvites().get(0).getInviteLink().toString();
    }
}
