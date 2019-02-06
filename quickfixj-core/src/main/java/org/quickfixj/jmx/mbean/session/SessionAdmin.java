/*******************************************************************************
 * Copyright (c) quickfixj.org  All rights reserved.
 *
 * This file is part of the QuickFIX/J FIX Engine
 *
 * This file may be distributed under the terms of the quickfixj.org
 * license as defined by quickfixj.org and appearing in the file
 * LICENSE included in the packaging of this file.
 *
 * This file is provided AS IS with NO WARRANTY OF ANY KIND, INCLUDING
 * THE WARRANTY OF DESIGN, MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE.
 *
 * See http://www.quickfixj.org/LICENSE for licensing information.
 *
 ******************************************************************************/

package org.quickfixj.jmx.mbean.session;

import org.quickfixj.QFJException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.SessionStateListener;
import quickfix.field.MsgType;
import quickfix.field.NewSeqNo;
import quickfix.field.converter.UtcTimestampConverter;

import javax.management.MBeanRegistration;
import javax.management.MBeanServer;
import javax.management.Notification;
import javax.management.NotificationBroadcasterSupport;
import javax.management.ObjectName;
import java.io.IOException;
import java.util.ArrayList;

public class SessionAdmin extends NotificationBroadcasterSupport implements SessionAdminMBean, MBeanRegistration, SessionStateListener {

	private static final String NOTIFICATION_TYPE = "quickfix.Session";

	private final Session session;

	private final Logger log = LoggerFactory.getLogger(getClass());

	private final ObjectName connectorName;

	private MBeanServer mbeanServer;

	private final ObjectName settingsName;

	public SessionAdmin(Session session, ObjectName connectorName, ObjectName settingsName) {
		this.session = session;
		this.connectorName = connectorName;
		this.settingsName = settingsName;
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.SessionMBean#getID()
	 */
	public String getID() {
		return session.getSessionID().toString();
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.SessionMBean#getTargetCompID()
	 */
	@Override
	public String getTargetCompID() {
		return session.getSessionID().getTargetCompID();
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.SessionMBean#getTargetSubID()
	 */
	@Override
	public String getTargetSubID() {
		return session.getSessionID().getTargetSubID();
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.SessionMBean#getTargetLocationID()
	 */
	@Override
	public String getTargetLocationID() {
		return session.getSessionID().getTargetLocationID();
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.SessionMBean#getSenderCompID()
	 */
	@Override
	public String getSenderCompID() {
		return session.getSessionID().getSenderCompID();
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.SessionMBean#getSenderSubID()
	 */
	@Override
	public String getSenderSubID() {
		return session.getSessionID().getSenderSubID();
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.SessionMBean#getSenderLocationID()
	 */
	@Override
	public String getSenderLocationID() {
		return session.getSessionID().getSenderLocationID();
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.SessionMBean#getBeginString()
	 */
	@Override
	public String getBeginString() {
		return session.getSessionID().getBeginString();
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.SessionMBean#isLoggedOn()
	 */
	@Override
	public boolean isLoggedOn() {
		return session.isLoggedOn();
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.SessionMBean#getRemoteIPAddress()
	 */
	public String getRemoteIPAddress() {
		String remoteAddress = session.getRemoteAddress();
		if (remoteAddress != null) {
			return remoteAddress;
		}
		return "";
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.SessionMBean#reset()
	 */
	@Override
	public void reset() throws IOException {
		logInvocation("reset");
		session.reset();
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.SessionMBean#getNextSenderMsgSeqNum()
	 */
	@Override
	public int getNextSenderMsgSeqNum() throws IOException {
		return session.getExpectedSenderNum();
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.QFJSessionMBean#setNextSenderMsgSeqNum(int)
	 */
	@Override
	public void setNextSenderMsgSeqNum(int next) throws IOException {
		logAttributeChange("NextSenderMsgSeqNum", next);
		session.setNextSenderMsgSeqNum(next);
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.QFJSessionMBean#setNextTargetMsgSeqNum(int)
	 */
	@Override
	public void setNextTargetMsgSeqNum(int next) throws IOException {
		logAttributeChange("NextTargetMsgSeqNum", next);
		session.setNextTargetMsgSeqNum(next);
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.SessionMBean#getNextTargetMsgSeqNum()
	 */
	@Override
	public int getNextTargetMsgSeqNum() throws IOException {
		return session.getExpectedTargetNum();
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.SessionMBean#getMessages(int, int)
	 */
	public String[] getMessages(int startSequence, int endSequence) throws IOException {
		ArrayList<String> messages = new ArrayList<>();
		session.getStore().get(startSequence, endSequence, messages);
		return messages.toArray(new String[messages.size()]);
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.QFJSessionMBean#disconnect()
	 */
	@Override
	public void disconnect() throws IOException {
		logInvocation("disconnect");
		session.disconnect("JMX admin", false);
	}

	@Override
	public void resetSequence(int nextSeqNum) throws SessionNotFound {
		logInvocation("resetSequence to: " + nextSeqNum);
		Message sequenceReset = new Message();
		sequenceReset.getHeader().setField(new MsgType(MsgType.SEQUENCE_RESET));
		sequenceReset.setField(new NewSeqNo(nextSeqNum));
		doSend(sequenceReset, session.getSessionID());
	}

	/**
	 * Helper method to be overridden by tests that handles sending out the message.
	 */
	protected void doSend(Message message, SessionID sessionID) throws SessionNotFound {
		Session.sendToTarget(message, sessionID);
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.QFJSessionMBean#logon()
	 */
	@Override
	public void logon() {
		logInvocation("logon");
		session.logon();
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.SessionMBean#logoff()
	 */
	@Override
	public void logoff() {
		logInvocation("logout");
		session.logout();
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.QFJSessionMBean#isReconnectEnabled()
	 */
	public boolean isReconnectEnabled() {
		return session.isEnabled();
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.SessionMBean#getHost()
	 */
	public String getHost() {
		try {
			return java.net.InetAddress.getLocalHost().getHostName();
		} catch (java.net.UnknownHostException uhe) {
			log.error(uhe.getMessage(), uhe);
			return "N/A";
		}
	}

	/* (non-Javadoc)
	 * @see quickfix.jmx.SessionMBean#getProcessID()
	 */
	public String getProcessID() {
		return System.getProperty("java.pid");
	}

	@Override
	public ObjectName getConnectorName() {
		return connectorName;
	}

	@Override
	public boolean getCheckCompID() {
		return session.getCheckCompID();
	}

	@Override
	public String getLogClassName() {
		return session.getLog().getClass().getName();
	}

	@Override
	public int getLogonTimeout() {
		return session.getLogonTimeout();
	}

	@Override
	public int getLogoutTimeout() {
		return session.getLogoutTimeout();
	}

	@Override
	public String getMessageFactoryClassName() {
		return session.getMessageFactory().getClass().getName();
	}

	@Override
	public String getMessageStoreClassName() {
		return session.getStore().getClass().getName();
	}

	@Override
	public boolean getRedundantResendRequestsAllowed() {
		return session.getRedundantResentRequestsAllowed();
	}

	@Override
	public boolean getRefreshOnLogon() {
		return session.getRefreshOnLogon();
	}

	@Override
	public boolean getResetOnDisconnect() {
		return session.getResetOnDisconnect();
	}

	@Override
	public boolean getResetOnLogout() {
		return session.getResetOnLogout();
	}

	@Override
	public boolean isLogonAlreadySent() {
		return session.isLogonAlreadySent();
	}

	@Override
	public boolean isLogonReceived() {
		return session.isLogonReceived();
	}

	@Override
	public boolean isLogonSendNeeded() {
		return session.isLogonSendNeeded();
	}

	@Override
	public boolean isLogonSent() {
		return session.isLogonSent();
	}

	@Override
	public boolean isLogonTimedOut() {
		return session.isLogonTimedOut();
	}

	@Override
	public boolean isLogoutReceived() {
		return session.isLogoutReceived();
	}

	@Override
	public boolean isLogoutSent() {
		return session.isLogoutSent();
	}

	@Override
	public boolean isLogoutTimedOut() {
		return session.isLogoutTimedOut();
	}

	@Override
	public void setLogonTimeout(int seconds) {
		logAttributeChange("LogonTimeout", seconds);
		session.setLogonTimeout(seconds);
	}

	@Override
	public void setLogoutTimeout(int seconds) {
		logAttributeChange("LogoutTimeout", seconds);
		session.setLogoutTimeout(seconds);
	}

	@Override
	public boolean isUsingDataDictionary() {
		return session.isUsingDataDictionary();
	}

	@Override
	public String getSessionID() {
		return session.getSessionID().toString();
	}

	@Override
	public boolean getEnabled() {
		return session.isEnabled();
	}

	@Override
	public String getStartTime() {
		try {
			return UtcTimestampConverter.convert(session.getStartTime(), true);
		} catch (IOException e) {
			return "[ERROR]";
		}
	}

	@Override
	public String getConnectionRole() {
		try {
			return mbeanServer.getAttribute(connectorName, "Role").toString();
		} catch (Exception e) {
			return "[ERROR: " + e.getMessage() + "]";
		}
	}

	private void logAttributeChange(String attributeName, int value) {
		session.getLog().onEvent("JMX: setting " + attributeName + " to " + value);
	}

	private void logInvocation(String operation) {
		session.getLog().onEvent("JMX: " + operation + " invoked");
	}

	@Override
	public void postDeregister() {
		try {
			mbeanServer.unregisterMBean(settingsName);
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new QFJException("Connector MBean postregistration failed", e);
		}
	}

	@Override
	public void postRegister(Boolean registrationDone) {
	}

	@Override
	public void preDeregister() throws Exception {
	}

	@Override
	public ObjectName preRegister(MBeanServer server, ObjectName name) throws Exception {
		mbeanServer = server;
		return name;
	}

	//
	// Session State Notifications
	//

	@Override
	public void onConnect() {
		sendNotification("connect");
	}

	@Override
	public void onDisconnect() {
		sendNotification("disconnect");
	}

	@Override
	public void onLogon() {
		sendNotification("logon");
	}

	@Override
	public void onLogout() {
		sendNotification("logout");
	}

	@Override
	public void onHeartBeatTimeout() {
		sendNotification("heartBeatTimeout");
	}

	@Override
	public void onMissedHeartBeat() {
		sendNotification("missedHeartBeat");
	}

	@Override
	public void onRefresh() {
		sendNotification("refresh");
	}

	@Override
	public void onReset() {
		sendNotification("reset");
	}

	private void sendNotification(String eventName) {
		Notification notification = new Notification(NOTIFICATION_TYPE, this,
				-1, System.currentTimeMillis(), eventName);
		sendNotification(notification);
	}

	@Override
	public void sendTestRequest() {
		session.generateTestRequest(System.currentTimeMillis() + "");
	}

	@Override
	public void sendHeartBeat() {
		session.generateHeartbeat();
	}

	@Override
	public void sendLogoutMessage() {
		session.generateLogout();
	}
}
