package org.tdl.vireo.integration;

import static org.junit.Assert.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.result.MockMvcResultHandlers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.tdl.vireo.annotations.Order;
import org.tdl.vireo.enums.Role;
import org.tdl.vireo.mock.interceptor.MockChannelInterceptor;
import org.tdl.vireo.model.User;
import org.tdl.vireo.model.repo.UserRepo;
import org.tdl.vireo.util.AuthUtility;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import edu.tamu.framework.model.Credentials;

public class UserIntegrationTest extends AbstractIntegrationTest {

	@Autowired
    private UserRepo userRepo;
		
    @Autowired
    private AuthUtility authUtility;
    
    @Before
    public void setup() {
    			
    	userRepo.create(TEST_USER2_EMAIL, TEST_USER2.getFirstName(), TEST_USER2.getLastName(), Role.ADMINISTRATOR);
    	userRepo.create(TEST_USER3_EMAIL, TEST_USER3.getFirstName(), TEST_USER3.getLastName(), Role.MANAGER);
    	userRepo.create(TEST_USER4_EMAIL, TEST_USER4.getFirstName(), TEST_USER4.getLastName(), Role.USER);
        
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
                        
        brokerChannelInterceptor = new MockChannelInterceptor();
        
        brokerChannel.addInterceptor(brokerChannelInterceptor);
        
        StompConnect();
    }

    @Test
    @Order(value = 1)
    public void testUserCredentialsOverStomp() throws InterruptedException, IOException {        
    	String responseJson = StompRequest("/user/credentials", null);
        
    	Map<String, Object> responseObject = objectMapper.readValue(responseJson, new TypeReference<Map<String, Object>>(){});

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) responseObject.get("payload");

        @SuppressWarnings("unchecked")
        Credentials shib = new Credentials((Map<String, String>) payload.get("Credentials"));
        
        assertEquals("Daniels", shib.getLastName());
        assertEquals("Jack", shib.getFirstName());
        assertEquals("aggieJack", shib.getNetid());
        assertEquals("123456789", shib.getUin());
        assertEquals("aggieJack@tamu.edu", shib.getEmail());
        assertEquals("ROLE_ADMIN", shib.getRole());
    }
    
    @Test
    @Order(value = 2)
    public void testUserCredentialsOverRest() throws Exception {    	    	
        mockMvc.perform(get("/user/credentials")
        					.contentType(MediaType.APPLICATION_JSON)
        					.header("jwt", jwtString))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.meta.type").value("SUCCESS"))
           .andExpect(jsonPath("$.payload.Credentials.firstName").value("Jack"))
           .andExpect(jsonPath("$.payload.Credentials.lastName").value("Daniels"))
           .andExpect(jsonPath("$.payload.Credentials.netid").value("aggieJack"))
           .andExpect(jsonPath("$.payload.Credentials.uin").value("123456789"))
           .andExpect(jsonPath("$.payload.Credentials.email").value("aggieJack@tamu.edu"))           
           .andExpect(jsonPath("$.payload.Credentials.role").value("ROLE_ADMIN"))
           .andDo(MockMvcResultHandlers.print());
    }
    
    @Test
    @Order(value = 3)
    public void testRegisterEmail() throws Exception {    	    	
        mockMvc.perform(get("/user/register")
        					.param("email", TEST_USER_EMAIL)
        					.contentType(MediaType.APPLICATION_JSON))           
           .andExpect(status().isOk())           
           .andExpect(jsonPath("$.meta.type").value("SUCCESS"))
           .andExpect(jsonPath("$.payload.UnmodifiableMap.email").value(TEST_USER_EMAIL))
           .andDo(MockMvcResultHandlers.print());
    }
    
    @Test
    @Order(value = 4)
    public void testRegister() throws Exception {    	
    	String token = authUtility.generateToken(TEST_USER_EMAIL, EMAIL_VERIFICATION_TYPE);    	
    	Map<String, String> data = new HashMap<String, String>();    	
    	data.put("token", token);
    	data.put("email", TEST_USER_EMAIL);
    	data.put("firstName", TEST_USER_FIRST_NAME);
    	data.put("lastName", TEST_USER_LAST_NAME);
    	data.put("password", TEST_USER_PASSWORD);
    	data.put("confirm", TEST_USER_CONFIRM);    	
        mockMvc.perform(get("/user/register")
        					.contentType(MediaType.APPLICATION_JSON)
        					.header("data", objectMapper.convertValue(data, JsonNode.class)))
           .andExpect(status().isOk())           
           .andExpect(jsonPath("$.meta.type").value("SUCCESS"))
           .andExpect(jsonPath("$.payload.User.email").value(TEST_USER_EMAIL))
           .andExpect(jsonPath("$.payload.User.firstName").value(TEST_USER_FIRST_NAME))
           .andExpect(jsonPath("$.payload.User.lastName").value(TEST_USER_LAST_NAME))
           .andExpect(jsonPath("$.payload.User.password").doesNotExist())
           .andDo(MockMvcResultHandlers.print());
    }
    
    @Test
    @Order(value = 5)
    public void testLogin() throws Exception {

    	testRegister();
    	
    	Map<String, String> data = new HashMap<String, String>();
    	data.put("email", TEST_USER_EMAIL);
    	data.put("password", TEST_USER_PASSWORD);
        mockMvc.perform(get("/user/login")
        					.contentType(MediaType.APPLICATION_JSON)
        					.header("data", objectMapper.convertValue(data, JsonNode.class)))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.meta.type").value("SUCCESS"))
           .andDo(MockMvcResultHandlers.print());
    }
    
    @Test
    @Order(value = 6)
    public void testAllUser() throws Exception {
		 	
    	String responseJson = StompRequest("/user/all", null);
    	 
    	Map<String, Object> responseObject = objectMapper.readValue(responseJson, new TypeReference<Map<String, Object>>(){});
		 
    	@SuppressWarnings("unchecked")
        Map<String, Object> contentObj = (Map<String, Object>) responseObject.get("payload");
		 
    	@SuppressWarnings("unchecked")
        Map<String, Object> mapObj = (Map<String, Object>) contentObj.get("HashMap");
		 
    	@SuppressWarnings("unchecked")
        List<Map<String, Object>> listMap =  (List<Map<String, Object>>) mapObj.get("list");

    	assertEquals(3, listMap.size());
    	
    	for(Map<String, Object> map : listMap) {
    		String email = (String) map.get("email");
    		switch(email) {	    		
    			case TEST_USER2_EMAIL: {
    				assertEquals(TEST_USER2.getFirstName(), (String) map.get("firstName"));
    				assertEquals(TEST_USER2.getLastName(), (String) map.get("lastName"));					 
    				assertEquals(TEST_USER2.getRole(), (String) map.get("role"));
    			}; break;
    			case TEST_USER3_EMAIL: {
    				assertEquals(TEST_USER3.getFirstName(), (String) map.get("firstName"));
    				assertEquals(TEST_USER3.getLastName(), (String) map.get("lastName"));					 
    				assertEquals(TEST_USER3.getRole(), (String) map.get("role"));
    			}; break;
    			case TEST_USER4_EMAIL: {
    				assertEquals(TEST_USER4.getFirstName(), (String) map.get("firstName"));
    				assertEquals(TEST_USER4.getLastName(), (String) map.get("lastName"));
    				assertEquals(TEST_USER4.getRole(), (String) map.get("role"));
    			}; break;
    		}
    	}		 
	 }
	 
	 @Test
	 @Order(value = 7)
	 public void testUpdateRole() throws Exception {
		 
		 testRegister();
		 
		 Map<String, String> data = new HashMap<String, String>();
		 data.put("email", TEST_USER_EMAIL);
		 data.put("role", TEST_USER_ROLE_UPDATE);
	    	
		 StompRequest("/user/update-role", data);
		 		 
		 User testUser = userRepo.findByEmail(TEST_USER_EMAIL);
		 
		 assertEquals(TEST_USER_FIRST_NAME, testUser.getFirstName());
		 assertEquals(TEST_USER_LAST_NAME, testUser.getLastName());
		 assertEquals(TEST_USER_EMAIL, testUser.getEmail());
		 assertEquals(TEST_USER_ROLE_UPDATE, testUser.getRole());
	 }
	 
	 @After
	 @Override
	 public void cleanup() {
		 userRepo.deleteAll();
	 }
	 
}
