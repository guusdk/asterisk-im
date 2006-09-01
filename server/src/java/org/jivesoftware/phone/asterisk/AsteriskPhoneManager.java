/**
 * $RCSfile: AsteriskPhoneManager.java,v $
 * $Revision: 1.13 $
 * $Date: 2005/07/02 00:22:51 $
 *
 * Copyright (C) 1999-2004 Jive Software. All rights reserved.
 *
 * This software is the proprietary information of Jive Software. Use is subject to license terms.
 */
package org.jivesoftware.phone.asterisk;

import net.sf.asterisk.manager.AuthenticationFailedException;
import net.sf.asterisk.manager.TimeoutException;
import org.jivesoftware.phone.*;
import org.jivesoftware.phone.database.PhoneDAO;
import org.jivesoftware.phone.element.PhoneEvent;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.Log;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;

import java.io.IOException;
import java.util.*;


/**
 * Asterisk dependent implementation of {@link PhoneManager}
 *
 * @author Andrew Wright
 * @since 1.0
 */
@PBXInfo(make = "Asterisk", version = "1.2")
public class AsteriskPhoneManager extends BasePhoneManager {

    private final Map<Long, CustomAsteriskManager> asteriskManagers
            = new HashMap<Long, CustomAsteriskManager>();
    AsteriskPlugin plugin;

    public AsteriskPhoneManager(PhoneDAO dao) {
        super(dao);
    }

    public void init(AsteriskPlugin plugin) {
        Log.info("Initializing Asterisk Manager connection");

        Collection<PhoneServer> servers = getPhoneServers();

        if (servers == null || servers.size() <= 0) {
            servers = loadLegacyServerConfiguration();
        }

        for (PhoneServer server : servers) {
            try {
                CustomAsteriskManager manager = connectToServer(server);

                if (manager != null) {
                    asteriskManagers.put(server.getID(), manager);
                }
            }
            catch (Throwable t) {
                Log.error("Error connecting to asterisk server " + server.getName(), t);
            }
        }

        this.plugin = plugin;
    }

    private Collection<PhoneServer> loadLegacyServerConfiguration() {
        // Populate the legacy manager configuration
        String serverAddress = JiveGlobals.getProperty(PhoneProperties.SERVER);
        String username = JiveGlobals.getProperty(PhoneProperties.USERNAME);
        String password = JiveGlobals.getProperty(PhoneProperties.PASSWORD);
        int port = JiveGlobals.getIntProperty(PhoneProperties.PORT, 5038);

        if (serverAddress == null || username == null || password == null || port <= 0) {
            return Collections.emptyList();  
        }

        PhoneServer server = createPhoneServer("Default Server", serverAddress, port, username,
                password);
        for(PhoneDevice device : getAllPhoneDevices()) {
            device.setServerID(server.getID());
        }

        // Loads the legacy server manager configuration into the database.
        return Arrays.asList(server);
    }


    private CustomAsteriskManager connectToServer(PhoneServer server) throws TimeoutException,
            IOException, AuthenticationFailedException {
        CustomAsteriskManager manager = null;
        // Check to see if the configuration is valid then
        // Initialize the manager connection pool and create an eventhandler
        if (server != null && server.getHostname() != null && server.getUsername() != null
                && server.getPassword() != null) {
            manager = new CustomAsteriskManager(server.getHostname(), server.getPort(),
                    server.getUsername(), server.getPassword());
            manager.logon();
            manager.addEventHandler(new AsteriskEventHandler(this));
        }
        else {
            Log.warn("AsteriskPlugin configuration is invalid, please see admin tool!!");
        }

        return manager;
    }

    public void destroy() {
        Log.debug("Shutting down Manager connections");
        for (CustomAsteriskManager manager : asteriskManagers.values()) {
            try {
                manager.logoff();
            }
            catch (Throwable e) {
                // Make sure we catch all exceptions show we can Log anything that might be
                // going on
                Log.error(e.getMessage(), e);
            }
        }
        asteriskManagers.clear();
    }

    public PhoneServerStatus getPhoneServerStatus(long serverID) {
        return asteriskManagers.containsKey(serverID) ? PhoneServerStatus.connected
                : PhoneServerStatus.disconnected;
    }

    @Override
    public void removePhoneServer(long serverID) {
        CustomAsteriskManager manager = asteriskManagers.remove(serverID);
        if(manager != null) {
            try {
                manager.logoff();
            }
            catch (Throwable e) {
                Log.error("Error disconnecting from asterisk manager", e);
            }
        }

        super.removePhoneServer(serverID);
    }

    public MailboxStatus mailboxStatus(long serverID, String mailbox) throws PhoneException {
        CustomAsteriskManager manager = asteriskManagers.get(serverID);
        if (manager != null) {
            manager.getMailboxStatus(mailbox);
        }
        return null;
    }

    public Map<Long, Collection<String>> getConfiguredDevices() throws PhoneException {
        Map<Long, Collection<String>> deviceMap = new HashMap<Long, Collection<String>>();
        for (Map.Entry<Long, CustomAsteriskManager> asteriskManager : asteriskManagers.entrySet()) {
            List<String> devices = asteriskManager.getValue().getSipDevices();
            Collections.sort(devices);
            deviceMap.put(asteriskManager.getKey(), devices);

            // todo Add IAX support
        }
        return Collections.unmodifiableMap(deviceMap);
    }

    public Collection<String> getConfiguredDevicesByServerID(long serverID) throws PhoneException {
        CustomAsteriskManager manager = asteriskManagers.get(serverID);
        if (manager != null) {
            return manager.getSipDevices();
        }
        return null;
    }

    public Map getStatus(long serverID) throws PhoneException {
        CustomAsteriskManager manager = asteriskManagers.get(serverID);
        if (manager != null) {
            return manager.getChannels();
        }
        return null;
    }

    public void dial(String username, String extension, JID jid) throws PhoneException {
        //acquire the jidUser object for the originating caller
        PhoneUser user = getPhoneUserByUsername(username);
        PhoneDevice primaryDevice = getPrimaryDevice(user.getID());

        // aquire the originating server
        CustomAsteriskManager manager = asteriskManagers.get(primaryDevice.getServerID());
        if (manager != null) {
            manager.dial(primaryDevice, extension);
        }
        else {
            throw new PhoneException("Not connected to originate phone server.");
        }
    }

    public boolean isReady() {
        return plugin.isComponentReady();
    }

    public void sendHangupMessage(String callSessionID, String device, String username) {
        Message message = new Message();
        message.setID(callSessionID);

        PhoneEvent phoneEvent = new PhoneEvent(callSessionID, PhoneEvent.Type.HANG_UP, device);
        message.getElement().add(phoneEvent);
        plugin.sendPacket2User(username, message);
    }

    public void sendPacket(Packet packet) {
        plugin.sendPacket(packet);
    }

    public void forward(String callSessionID, String username, String extension, JID jid)
            throws PhoneException {
        CallSession phoneSession = CallSessionFactory.getCallSessionFactory()
                .getCallSession(callSessionID);
        if (phoneSession == null) {
            throw new PhoneException("Call session not currently stored in Asterisk-IM");
        }
        CustomAsteriskManager phoneManager = asteriskManagers.get(phoneSession.getServerID());
        if(phoneManager == null) {
            throw new PhoneException("Not connected to asterisk server to forward call");
        }
        phoneManager.forward(phoneSession, username, extension, jid);
    }

    @Override
    public PhoneServer createPhoneServer(String name, String serverAddress, int port,
                                         String username, String password)
    {
        PhoneServer server = super.createPhoneServer(name, serverAddress, port, username, password);
        try {
            CustomAsteriskManager manager = connectToServer(server);

            if (manager != null) {
                asteriskManagers.put(server.getID(), manager);
            }
        }
        catch (Throwable t) {
            Log.error("Error connecting to " + name + " phone server", t);
        }
        return server;
    }
}
