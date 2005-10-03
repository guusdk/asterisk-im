/**
 * $RCSfile: AsteriskEventHandler.java,v $
 * $Revision: 1.10 $
 * $Date: 2005/07/01 23:56:27 $
 *
 * Copyright (C) 1999-2004 Jive Software. All rights reserved.
 *
 * This software is the proprietary information of Jive Software. Use is subject to license terms.
 */
package org.jivesoftware.phone.asterisk;

import net.sf.asterisk.manager.ManagerEventHandler;
import net.sf.asterisk.manager.event.*;
import org.dom4j.Attribute;
import org.dom4j.Element;
import org.jivesoftware.messenger.ClientSession;
import org.jivesoftware.messenger.SessionManager;
import org.jivesoftware.messenger.XMPPServer;
import static org.jivesoftware.messenger.XMPPServer.getInstance;
import org.jivesoftware.phone.CallSession;
import static org.jivesoftware.phone.CallSessionFactory.getCallSessionFactory;
import org.jivesoftware.phone.PhoneManager;
import static org.jivesoftware.phone.PhoneManagerFactory.close;
import static org.jivesoftware.phone.PhoneManagerFactory.getPhoneManager;
import org.jivesoftware.phone.PhoneUser;
import org.jivesoftware.phone.CallSessionFactory;
import static org.jivesoftware.phone.asterisk.AsteriskUtil.getDevice;
import org.jivesoftware.phone.element.PhoneEvent;
import org.jivesoftware.phone.element.PhoneEvent.Type;
import org.jivesoftware.phone.element.PhoneStatus;
import org.jivesoftware.phone.element.PhoneStatus.Status;
import org.jivesoftware.phone.util.PhoneConstants;
import static org.jivesoftware.phone.util.ThreadPool.getThreadPool;
import org.jivesoftware.util.StringUtils;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Presence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Used to handle all events coming from Asterisk. Various tasks will be executed based on tasks
 * that we are expecting, such as set a users status as away when they answer the phone.
 *
 * @author Andrew Wright
 */
public class AsteriskEventHandler implements ManagerEventHandler, PhoneConstants {

    private static final Logger log = Logger.getLogger(AsteriskEventHandler.class.getName());

    /**
     * Used to store old presence objects before a person goes on the phone
     */
    private Map<String, Collection<Presence>> previousPresenceMap =
            new ConcurrentHashMap<String, Collection<Presence>>();


    private AsteriskPlugin asteriskPlugin;

    public AsteriskEventHandler(AsteriskPlugin asteriskPlugin) {
        this.asteriskPlugin = asteriskPlugin;
    }


    public void handleEvent(ManagerEvent event) {

        if (event instanceof ChannelEvent) {
            handleChannelEvent((ChannelEvent) event);
        } else if (event instanceof LinkEvent) {
            getThreadPool().execute(new LinkTask((LinkEvent) event));
        } else if (event instanceof NewExtenEvent) {
            NewExtenEvent neEvent = (NewExtenEvent) event;

            if ("Dial".equals(neEvent.getApplication())) {
                getThreadPool().execute(new DialedTask(neEvent));
            }
        }

    }

    public void handleChannelEvent(ChannelEvent event) {

        ExecutorService executor = getThreadPool();

        if (executor == null) {
            log.severe("Phone Thread pool was not initialized, returning!");
            return;
        } else if (executor.isShutdown()) {
            log.warning("Phone Thread pool has been shutdown, plugin shutdown must be in progress! " +
                    "Not processing event");
            return;
        }

        if (event instanceof NewStateEvent) {
            NewStateEvent nsEvent = (NewStateEvent) event;
            String state = nsEvent.getState();
            if ("Up".equals(state)) {
                executor.execute(new OnPhoneTask(nsEvent));
            }

        } else if (event instanceof NewChannelEvent) {
            NewChannelEvent ncEvent = (NewChannelEvent) event;
            String state = ncEvent.getState();

            // This will actually cause the ring task to be potentially executed twice
            // Once for the Ring state and then again for the Ringing state.
            // I am not sure if I will eventually change this just to work for the ring state
            // Or keep it for both
            if (state.equals("Ringing")) {
                executor.execute(new RingTask(ncEvent));
            }

        } else if (event instanceof HangupEvent) {
            executor.execute(new HangupTask((HangupEvent) event));
        }

    }

    /**
     * Adds link event information into the call session
     */
    private class LinkTask implements Runnable {

        private LinkEvent event;

        public LinkTask(LinkEvent event) {
            this.event = event;
        }

        public void run() {

            PhoneManager phoneManager = null;

            try {
                phoneManager = getPhoneManager();

                String device = getDevice(event.getChannel1());

                PhoneUser phoneUser = phoneManager.getByDevice(device);

                if (phoneUser != null) {
                    CallSession callSession = getCallSessionFactory().getCallSession(event.getUniqueId1(), phoneUser.getUsername());
                    callSession.setChannel(event.getChannel1());
                    callSession.setLinkedChannel(event.getChannel2());
                }

                // Now setup this up for the second user
                device = getDevice(event.getChannel2());
                phoneUser = phoneManager.getByDevice(device);

                if (phoneUser != null) {
                    CallSession callSession = getCallSessionFactory().getCallSession(event.getUniqueId2(), phoneUser.getUsername());
                    callSession.setChannel(event.getChannel2());
                    callSession.setLinkedChannel(event.getChannel1());
                }

            }
            catch (Exception e) {
                log.log(Level.SEVERE, e.getMessage(), e);
            }
            finally {
                close(phoneManager);
            }


        }


    }

    /**
     * This task is used to handle events where a user has just answered the phone.
     */
    private class OnPhoneTask implements Runnable {

        private NewStateEvent event;

        public OnPhoneTask(NewStateEvent event) {
            this.event = event;
        }

        public void run() {

            //everything after the hyphen should be skipped
            String device = getDevice(event.getChannel());

            PhoneManager phoneManager = null;

            try {
                phoneManager = getPhoneManager();
                PhoneUser phoneUser = phoneManager.getByDevice(device);

                //If there is no jid for this device don't do anything else
                if (phoneUser == null) {
                    log.finer("AnswerTask: Could not find device/jid mapping for device " +
                            device + " returning");
                    return;
                }

                CallSession callSession = getCallSessionFactory().getCallSession(event.getUniqueId(), phoneUser.getUsername());

                JID jid = getJID(phoneUser);


                XMPPServer server = getInstance();

                // Notify the client that they have answered the phone
                Message message = new Message();
                message.setFrom(asteriskPlugin.getComponentJID());
                message.setID(event.getUniqueId());

                PhoneEvent phoneEvent =
                        new PhoneEvent(callSession.getId(), Type.ON_PHONE, device);
                phoneEvent.addElement("callerID").setText(callSession.getCallerID());
                message.getElement().add(phoneEvent);

                //Acquire the xmpp sessions for the user
                SessionManager sessionManager = server.getSessionManager();
                Collection<ClientSession> sessions = sessionManager.getSessions(jid.getNode());

                // We don't care about people without a session
                if (sessions.size() == 0) {
                    return;
                }

                log.finer("AnswerTask: setting presence to away for " + jid);

                // If we haven't determined their original presence, set it
                // If there is already and original presence, it means the user is
                // receiving an additional phone call. In this case we should not save the presence
                // because the current precense would also be "on phone"
                if (previousPresenceMap.get(phoneUser.getUsername()) == null) {

                    // Iterate through all of the sessions sending out new presences for each
                    Presence presence = new Presence();
                    presence.setShow(Presence.Show.away);
                    presence.setStatus("On the phone");

                    PhoneStatus phoneStatus = new PhoneStatus(Status.ON_PHONE);
                    presence.getElement().add(phoneStatus);


                    Collection<Presence> prevPresences = new ArrayList<Presence>();

                    for (ClientSession session : sessions) {

                        message.setTo(session.getAddress());
                        asteriskPlugin.sendPacket(message);


                        Presence prevPresence = session.getPresence();
                        prevPresences.add(prevPresence);

                        JID fullJID = session.getAddress();
                        presence.setFrom(fullJID);

                        server.getPresenceRouter().route(presence);
                    }

                    previousPresenceMap.put(phoneUser.getUsername(), prevPresences);
                }
            }
            catch (Exception e) {
                log.log(Level.SEVERE, e.getMessage(), e);
            }
            finally {
                close(phoneManager);
            }
        }

    }

    /**
     * This task is used to handle events where a user has just hang up the phone
     */
    private class HangupTask implements Runnable {

        private HangupEvent event;

        public HangupTask(HangupEvent event) {
            this.event = event;
        }

        public void run() {
            //everything after the hyphen should be skipped
            String device = getDevice(event.getChannel());

            PhoneManager phoneManager = null;

            try {
                phoneManager = getPhoneManager();
                PhoneUser phoneUser = phoneManager.getByDevice(device);

                //If there is no jid for this device don't do anything else
                if (phoneUser == null) {
                    return;
                }

                Message message = new Message();
                message.setFrom(asteriskPlugin.getComponentJID());
                message.setID(event.getUniqueId());

                PhoneEvent phoneEvent =
                        new PhoneEvent(event.getUniqueId(), Type.HANG_UP, device);
                message.getElement().add(phoneEvent);

                // Send the message to each of jids for this user
                SessionManager sessionManager = XMPPServer.getInstance().getSessionManager();
                Collection<ClientSession> sessions = sessionManager.getSessions(phoneUser.getUsername());
                for (ClientSession session : sessions) {
                    message.setTo(session.getAddress());
                    asteriskPlugin.sendPacket(message);
                }


                CallSessionFactory callSessionFactory = getCallSessionFactory();

                // If the user does not have any more call sessions, set back
                // the presence to what it was before they received any calls
                if(callSessionFactory.getUserCallSessions(phoneUser.getUsername()).size() == 1) {

                    // Set the user's presence back to what it was before the phone call
                    Collection<Presence> presences = previousPresenceMap.remove(phoneUser.getUsername());
                    if (presences != null) {
                        for (Presence presence : presences) {

                            Element presenceElement = presence.getElement();

                            Element phoneStatusElement = presenceElement.element("phone-status");
                            // If the phone-status attribute exists check to see if the
                            if (phoneStatusElement != null) {

                                Attribute statusAtt = phoneStatusElement.attribute("status");

                                if (!Status.AVAILABLE.name().equals(statusAtt.getText())) {
                                    statusAtt.setText(Status.AVAILABLE.name());
                                }

                            }
                            // The attribute doesn't exist add new attribute
                            else {

                                PhoneStatus status = new PhoneStatus(Status.AVAILABLE);
                                presence.getElement().add(status);

                            }

                            getInstance().getPresenceRouter().route(presence);
                        }
                    }
                }

                // finally destroy the session.
                callSessionFactory.destroyPhoneSession(event.getUniqueId());

            }
            catch (Exception e) {
                log.log(Level.SEVERE, e.getMessage(), e);
            }
            finally {
                close(phoneManager);
            }
        }
    }

    /**
     * Used to send a message to a user when their phone is ringing
     */
    private class RingTask implements Runnable {

        private NewChannelEvent event;

        public RingTask(NewChannelEvent event) {
            this.event = event;
        }

        public void run() {

            String device = getDevice(event.getChannel());

            PhoneManager phoneManager = null;

            try {
                phoneManager = getPhoneManager();
                PhoneUser phoneUser = phoneManager.getByDevice(device);

                //If there is no jid for this device don't do anything else
                if (phoneUser == null) {
                    return;
                }

                // try and see if we have a fake call session
                // This will be created if there was an originated call
                CallSession fakeSession = getCallSessionFactory().destroyPhoneSession(device);


                CallSession callSession = getCallSessionFactory()
                        .getCallSession(event.getUniqueId(), phoneUser.getUsername());

                callSession.setChannel(event.getChannel());

                Message message = new Message();
                message.setID(event.getUniqueId()); //just put something in here
                message.setFrom(asteriskPlugin.getComponentJID());

                if (fakeSession != null) {

                    callSession.setCallerID(fakeSession.getCallerID());
                    callSession.setDialedJID(fakeSession.getDialedJID());

                    PhoneEvent dialEvent =
                            new PhoneEvent(event.getUniqueId(), Type.DIALED, device);

                    dialEvent.addElement("callerID").setText(fakeSession.getCallerID());
                    callSession.setCallerID(fakeSession.getCallerID());

                    if (fakeSession.getDialedJID() != null) {
                        dialEvent.addElement("jid", fakeSession.getDialedJID().toString());
                    }
                    message.getElement().add(dialEvent);

                } else {
                    PhoneEvent phoneEvent =
                            new PhoneEvent(event.getUniqueId(), Type.RING, device);
                    String callerID = StringUtils.stripTags(event.getCallerId());
                    phoneEvent.addElement("callerID").setText(callerID);
                    callSession.setCallerID(callerID);

                    message.getElement().add(phoneEvent);
                }

                // Send the message to each of jids for this user
                SessionManager sessionManager = XMPPServer.getInstance().getSessionManager();
                Collection<ClientSession> sessions = sessionManager.getSessions(phoneUser.getUsername());
                for (ClientSession session : sessions) {
                    message.setTo(session.getAddress());
                    asteriskPlugin.sendPacket(message);
                }
            }
            catch (Exception e) {
                log.log(Level.SEVERE, e.getMessage(), e);
            }
            finally {
                close(phoneManager);
            }
        }
    }


    /**
     * Used to send a message to a user when their phone is ringing
     * <p/>
     * This is based of the NewExten event because we needed to use appdata information
     * for coming up with a usable callerID from things like Zap devices
     */
    private class DialedTask implements Runnable {

        private NewExtenEvent event;

        public DialedTask(NewExtenEvent event) {
            this.event = event;
        }

        public void run() {

            String device = getDevice(event.getChannel());

            PhoneManager phoneManager = null;

            try {
                phoneManager = getPhoneManager();
                PhoneUser phoneUser = phoneManager.getByDevice(device);

                //If there is no jid for this device don't do anything else
                if (phoneUser == null) {
                    return;
                }


                CallSession callSession = getCallSessionFactory().getCallSession(event.getUniqueId(), phoneUser.getUsername());
                if (callSession.getCallerID() != null) {
                    // We have already reported a Dial event to the user, no need to do it gain
                    return;
                }

                callSession.setChannel(event.getChannel());

                Message message = new Message();
                message.setID(event.getUniqueId());
                message.setFrom(asteriskPlugin.getComponentJID());

                String appData = event.getAppData();

                String callerID;

                if (appData != null) {
                    if (appData.contains("Zap/")) {
                        String[] tokens = appData.split("/");
                        callerID = tokens[tokens.length - 1];
                    } else if (appData.contains("IAX/") || appData.contains("SIP/")) {

                        // Hopefully they used useful names like SIP/exten
                        String name = getDevice(appData);

                        // string may be like this SIP/6131&SIP/232|20
                        int index = name.indexOf("|");
                        if (index > 0) {
                            name = name.substring(0, index);
                        }

                        // string may be like SIP/6131&SIP/232
                        index = name.indexOf("&");
                        if (index > 0) {
                            name = name.substring(0, index);
                        }

                        // string will be like SIP/6131
                        index = name.indexOf("/");
                        name = name.substring(index + 1);

                        callerID = name;
                    } else {
                        // Whatever it is use it (hack)
                        callerID = appData;
                    }
                } else {
                    //finally just put something in there (hack)
                    callerID = event.getUniqueId();
                }
                callSession.setCallerID(callerID);

                PhoneEvent phoneEvent =
                        new PhoneEvent(event.getUniqueId(), Type.DIALED, device);
                message.getElement().add(phoneEvent);

                phoneEvent.addElement("callerID").setText(callerID);

                // Send the message to each of jids for this user
                SessionManager sessionManager = XMPPServer.getInstance().getSessionManager();
                Collection<ClientSession> sessions = sessionManager.getSessions(phoneUser.getUsername());
                for (ClientSession session : sessions) {
                    message.setTo(session.getAddress());
                    asteriskPlugin.sendPacket(message);
                }
            }
            catch (Exception e) {
                log.log(Level.SEVERE, e.getMessage(), e);
            }
            finally {
                close(phoneManager);
            }
        }
    }

    private JID getJID(PhoneUser user) {
        String serverName = getInstance().getServerInfo().getName();
        return new JID(user.getUsername(), serverName, null);
    }
}
