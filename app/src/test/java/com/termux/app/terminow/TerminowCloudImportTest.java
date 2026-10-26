package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * The cloud response parsers: DigitalOcean droplets JSON and EC2 DescribeInstances XML map into the
 * shared instance model, pick public/private addresses correctly, derive the region, and read
 * pagination markers — all from captured bodies, with no token, credentials or network.
 */
public class TerminowCloudImportTest {

    private static final String DROPLETS =
        "{\"droplets\":[" +
        "{\"id\":3164444,\"name\":\"web-1\",\"status\":\"active\"," +
        "\"region\":{\"slug\":\"nyc3\",\"name\":\"New York 3\"}," +
        "\"networks\":{\"v4\":[" +
        "{\"ip_address\":\"10.128.0.1\",\"type\":\"private\"}," +
        "{\"ip_address\":\"203.0.113.9\",\"type\":\"public\"}]}," +
        "\"tags\":[\"web\",\"prod\"]}," +
        "{\"id\":3164445,\"name\":\"db-1\",\"status\":\"off\"," +
        "\"region\":{\"slug\":\"nyc3\"},\"networks\":{\"v4\":[" +
        "{\"ip_address\":\"10.128.0.2\",\"type\":\"private\"}]}}" +
        "]," +
        "\"links\":{\"pages\":{\"next\":\"https://api.digitalocean.com/v2/droplets?page=2&per_page=2\"}}}";

    @Test
    public void parsesDropletsWithAddressesRegionAndTags() throws Exception {
        List<TerminowCloudInstance> droplets = TerminowDigitalOceanImport.parseDroplets(DROPLETS);
        assertEquals(2, droplets.size());

        TerminowCloudInstance web = droplets.get(0);
        assertEquals("web-1", web.name);
        assertEquals("3164444", web.id);
        assertEquals("active", web.state);
        assertEquals("nyc3", web.region);
        assertEquals("203.0.113.9", web.publicIp);
        assertEquals("10.128.0.1", web.privateIp);
        assertEquals("203.0.113.9", web.address(true));
        assertEquals("10.128.0.1", web.address(false));
        assertTrue(web.tags.contains("web"));

        TerminowCloudInstance db = droplets.get(1);
        assertEquals("", db.publicIp);
        // With no public IP, asking for public falls back to the private one.
        assertEquals("10.128.0.2", db.address(true));
    }

    @Test
    public void readsDropletPaginationLink() {
        assertEquals("https://api.digitalocean.com/v2/droplets?page=2&per_page=2",
            TerminowDigitalOceanImport.nextPageUrl(DROPLETS));
        assertEquals("", TerminowDigitalOceanImport.nextPageUrl("{\"droplets\":[]}"));
    }

    @Test
    public void malformedDropletsJsonIsRejected() {
        try {
            TerminowDigitalOceanImport.parseDroplets("{not json");
            org.junit.Assert.fail("Expected malformed JSON to throw");
        } catch (Exception expected) {
            // Good.
        }
    }

    private static final String EC2 =
        "<DescribeInstancesResponse xmlns=\"http://ec2.amazonaws.com/doc/2016-11-15/\">" +
        "<reservationSet><item><instancesSet><item>" +
        "<instanceId>i-0abcd1234</instanceId>" +
        "<instanceState><code>16</code><name>running</name></instanceState>" +
        "<privateIpAddress>10.0.1.5</privateIpAddress>" +
        "<ipAddress>54.210.1.2</ipAddress>" +
        "<placement><availabilityZone>us-east-1a</availabilityZone></placement>" +
        "<tagSet>" +
        "<item><key>Name</key><value>bastion</value></item>" +
        "<item><key>env</key><value>prod</value></item>" +
        "</tagSet>" +
        "</item></instancesSet></item></reservationSet>" +
        "<nextToken>TOKEN123</nextToken>" +
        "</DescribeInstancesResponse>";

    @Test
    public void parsesEc2InstanceWithNameRegionStateAndIps() throws Exception {
        List<TerminowCloudInstance> instances = TerminowEc2Import.parseDescribeInstances(EC2);
        assertEquals(1, instances.size());
        TerminowCloudInstance c = instances.get(0);
        assertEquals("bastion", c.name);
        assertEquals("i-0abcd1234", c.id);
        assertEquals("running", c.state);
        assertEquals("54.210.1.2", c.publicIp);
        assertEquals("10.0.1.5", c.privateIp);
        assertEquals("us-east-1", c.region);
        assertTrue(c.tags.contains("env=prod"));
    }

    @Test
    public void readsEc2NextToken() {
        assertEquals("TOKEN123", TerminowEc2Import.nextToken(EC2));
    }

    @Test
    public void regionFromAzStripsZoneLetter() {
        assertEquals("us-east-1", TerminowEc2Import.regionFromAz("us-east-1a"));
        assertEquals("ap-southeast-2", TerminowEc2Import.regionFromAz("ap-southeast-2c"));
        assertEquals("us-east-1", TerminowEc2Import.regionFromAz("us-east-1"));
    }

    @Test
    public void ec2WithNoInstancesParsesEmpty() throws Exception {
        String xml = "<DescribeInstancesResponse><reservationSet/></DescribeInstancesResponse>";
        assertTrue(TerminowEc2Import.parseDescribeInstances(xml).isEmpty());
    }
}
