package edu.harvard.iq.dataverse.api;

import edu.harvard.iq.dataverse.util.MockResponse;
import java.io.StringReader;
import java.util.logging.Logger;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.ws.rs.core.Response;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class AbstractApiBeanTest {

    private static final Logger logger = Logger.getLogger(AbstractApiBeanTest.class.getCanonicalName());

    AbstractApiBeanImpl sut;

    @Before
    public void before() {
        sut = new AbstractApiBeanImpl();
    }

    @Test
    public void testIsNumeric() {
        assertTrue(sut.isNumeric("1"));
        assertTrue(sut.isNumeric("199999"));
        assertFalse(sut.isNumeric("a"));
    }

    @Test
    public void testParseBooleanOrDie_ok() throws Exception {
        assertTrue(sut.parseBooleanOrDie("1"));
        assertTrue(sut.parseBooleanOrDie("yes"));
        assertTrue(sut.parseBooleanOrDie("true"));
        assertFalse(sut.parseBooleanOrDie("false"));
        assertFalse(sut.parseBooleanOrDie("0"));
        assertFalse(sut.parseBooleanOrDie("no"));
    }

    @Test(expected = Exception.class)
    public void testParseBooleanOrDie_invalid() throws Exception {
        sut.parseBooleanOrDie("I'm not a boolean value!");
    }

    @Test
    public void testFailIfNull_ok() throws Exception {
        sut.failIfNull(sut, "");
    }

    @Test
    public void testAllowCors() {
        Response r = sut.allowCors(new MockResponse(200));
        assertEquals("*", r.getHeaderString("Access-Control-Allow-Origin"));
    }

    @Test
    public void testMessages() {
        String message = "myMessage";
        JsonObjectBuilder jsonObjectBuilder = Json.createObjectBuilder();
        Response response = sut.ok(message, jsonObjectBuilder);
        JsonReader jsonReader = Json.createReader(new StringReader((String) response.getEntity().toString()));
        JsonObject jsonObject = jsonReader.readObject();
        logger.info("jsonObject: " + jsonObject);
        assertEquals(message, jsonObject.getString("message"));
    }

    @Test
    public void testMessagesNull() {
        try {
            sut.ok(null, Json.createObjectBuilder());
        } catch (NullPointerException ex) {
            /**
             * @todo Should this really be a NullPointerException?
             */
            assertEquals("message cannot be null", ex.getMessage());
        }
        try {
            sut.ok("myMessage", null);
        } catch (NullPointerException ex) {
            /**
             * @todo Should this really be a NullPointerException?
             */
            assertEquals("jsonObjectBuilder cannot be null", ex.getMessage());
        }
    }

    /**
     * dummy implementation
     */
    public class AbstractApiBeanImpl extends AbstractApiBean {

    }

}
