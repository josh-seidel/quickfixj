/*******************************************************************************
 * Copyright (c) quickfixengine.org  All rights reserved.
 *
 * This file is part of the QuickFIX FIX Engine
 *
 * This file may be distributed under the terms of the quickfixengine.org
 * license as defined by quickfixengine.org and appearing in the file
 * LICENSE included in the packaging of this file.
 *
 * This file is provided AS IS with NO WARRANTY OF ANY KIND, INCLUDING
 * THE WARRANTY OF DESIGN, MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE.
 *
 * See http://www.quickfixengine.org/LICENSE for licensing information.
 *
 * Contact ask@quickfixengine.org if any conditions of this licensing
 * are not clear to you.
 ******************************************************************************/

package quickfix;

import org.quickfixj.CharsetSupport;
import org.quickfixj.QFJException;
import quickfix.Message.Header;
import quickfix.field.ApplVerID;
import quickfix.field.BeginString;
import quickfix.field.DefaultApplVerID;
import quickfix.field.MsgType;
import quickfix.field.SenderCompID;
import quickfix.field.SenderLocationID;
import quickfix.field.SenderSubID;
import quickfix.field.TargetCompID;
import quickfix.field.TargetLocationID;
import quickfix.field.TargetSubID;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

public class MessageUtils {

    private static final char FIELD_SEPARATOR = '\001';

    public static SessionID getSessionID(final Message fixMessage) {
        final Header header = fixMessage.getHeader();
        return new SessionID(getFieldOrDefault(header, BeginString.FIELD, null), getFieldOrDefault(
                header, SenderCompID.FIELD, null), getFieldOrDefault(header, SenderSubID.FIELD,
                null), getFieldOrDefault(header, SenderLocationID.FIELD, null), getFieldOrDefault(
                header, TargetCompID.FIELD, null), getFieldOrDefault(header, TargetSubID.FIELD,
                null), getFieldOrDefault(header, TargetLocationID.FIELD, null), null);
    }

    public static SessionID getSessionID(final String messageString) {
        return new SessionID(getStringField(messageString, BeginString.FIELD), getStringField(
                messageString, SenderCompID.FIELD),
                getStringField(messageString, SenderSubID.FIELD), getStringField(messageString,
                        SenderLocationID.FIELD), getStringField(messageString, TargetCompID.FIELD),
                getStringField(messageString, TargetSubID.FIELD), getStringField(messageString,
                        TargetLocationID.FIELD), null);
    }

    public static SessionID getReverseSessionID(final Message fixMessage) {
        final Header header = fixMessage.getHeader();
        return new SessionID(getFieldOrDefault(header, BeginString.FIELD, null), getFieldOrDefault(
                header, TargetCompID.FIELD, null), getFieldOrDefault(header, TargetSubID.FIELD,
                null), getFieldOrDefault(header, TargetLocationID.FIELD, null), getFieldOrDefault(
                header, SenderCompID.FIELD, null), getFieldOrDefault(header, SenderSubID.FIELD,
                null), getFieldOrDefault(header, SenderLocationID.FIELD, null), null);
    }

    public static SessionID getReverseSessionID(final String messageString) {
        return new SessionID(getStringField(messageString, BeginString.FIELD), getStringField(
                messageString, TargetCompID.FIELD),
                getStringField(messageString, TargetSubID.FIELD), getStringField(messageString,
                        TargetLocationID.FIELD), getStringField(messageString, SenderCompID.FIELD),
                getStringField(messageString, SenderSubID.FIELD), getStringField(messageString,
                        SenderLocationID.FIELD), null);
    }

    private static String getFieldOrDefault(final FieldMap fields, final int tag, final String defaultValue) {
        if (fields.isSetField(tag)) {
            try {
                return fields.getString(tag);
            } catch (final FieldNotFound e) {
                // ignore, should never happen
                return null;
            }
        } else {
            return defaultValue;
        }
    }

    public static Message parse(final MessageFactory messageFactory, final DataDictionary dataDictionary,
            final String messageString) throws InvalidMessage {
        return parse(messageFactory, dataDictionary, messageString, true);
    }

    /**
     * Utility method for parsing a message. This should only be used for parsing messages from
     * FIX versions 4.4 or earlier.
     *
     * @param messageFactory
     * @param dataDictionary
     * @param messageString
     * @return the parsed message
     * @throws InvalidMessage
     */
    public static Message parse(final MessageFactory messageFactory, final DataDictionary dataDictionary,
            final String messageString, final boolean validateChecksum) throws InvalidMessage {
        final int index = messageString.indexOf(FIELD_SEPARATOR);
        if (index < 0) {
            throw new InvalidMessage("Message does not contain any field separator");
        }
        final String beginString = messageString.substring(2, index);
        final String messageType = getMessageType(messageString);
        final quickfix.Message message = messageFactory.create(beginString, messageType);
        message.fromString(messageString, dataDictionary, dataDictionary != null, validateChecksum);
        return message;
    }

    /**
     * NOTE: This method is intended for internal use.
     *
     * @param session the Session that will process the message
     * @param messageString
     * @return the parsed message
     * @throws InvalidMessage
     */
    public static Message parse(final Session session, final String messageString) throws InvalidMessage {
        final String beginString = getStringField(messageString, BeginString.FIELD);
        final String msgType = getMessageType(messageString);

        ApplVerID applVerID;

        if (FixVersions.BEGINSTRING_FIXT11.equals(beginString)) {
            applVerID = getApplVerID(session, messageString);
        } else {
            applVerID = toApplVerID(beginString);
        }

        final MessageFactory messageFactory = session.getMessageFactory();

        final DataDictionaryProvider ddProvider = session.getDataDictionaryProvider();
        final DataDictionary sessionDataDictionary = ddProvider == null ? null : ddProvider
                .getSessionDataDictionary(beginString);
        final DataDictionary applicationDataDictionary = ddProvider == null ? null : ddProvider
                .getApplicationDataDictionary(applVerID);

        final quickfix.Message message = messageFactory.create(beginString, applVerID, msgType);
        final DataDictionary payloadDictionary = MessageUtils.isAdminMessage(msgType)
                ? sessionDataDictionary
                : applicationDataDictionary;

        final boolean doValidation = payloadDictionary != null;
        final boolean validateChecksum = session.isValidateChecksum();

        message.parse(messageString, sessionDataDictionary, payloadDictionary, doValidation,
                validateChecksum);

        return message;
    }

    private static ApplVerID getApplVerID(final Session session, final String messageString)
            throws InvalidMessage {
        ApplVerID applVerID = null;

        final String applVerIdString = getStringField(messageString, ApplVerID.FIELD);
        if (applVerIdString != null) {
            applVerID = new ApplVerID(applVerIdString);
        }

        if (applVerID == null) {
            applVerID = session.getTargetDefaultApplicationVersionID();
        }

        if (applVerID == null && isLogon(messageString)) {
            final String defaultApplVerIdString = getStringField(messageString,
                    DefaultApplVerID.FIELD);
            if (defaultApplVerIdString != null) {
                applVerID = new ApplVerID(defaultApplVerIdString);
            }
        }

        if (applVerID == null) {
            throw newInvalidMessageException("Can't determine ApplVerID from message " + messageString, getMinimalMessage(messageString));
        }

        return applVerID;
    }

    public static boolean isAdminMessage(final String msgType) {
        return msgType.length() == 1 && "0A12345".contains(msgType);
    }

    public static boolean isHeartbeat(final String message) {
        return isMessageType(message, MsgType.HEARTBEAT);
    }

    public static boolean isLogon(final String message) {
        return isMessageType(message, MsgType.LOGON);
    }

    private static boolean isMessageType(final String message, final String msgType) {
        try {
            return msgType.equals(getMessageType(message));
        } catch (final InvalidMessage e) {
            return false;
        }
    }

    public static String getMessageType(final String messageString) throws InvalidMessage {
        final String value = getStringField(messageString, 35);
        if (value == null) {
            throw newInvalidMessageException("Missing or garbled message type in " + messageString, getMinimalMessage(messageString));
        }
        return value;
    }

    /**
     * Tries to set MsgSeqNum and MsgType from a FIX string to a new Message.
     * These fields are referenced on the outgoing Reject message.
     *
     * @param messageString FIX message as String
     * @return New quickfix.Message with optionally set header fields MsgSeqNum
     * and MsgType.
     */
    static Message getMinimalMessage(final String messageString) {
        final Message tempMessage = new Message();
        final String seqNum = getStringField(messageString, 34);
        if (seqNum != null) {
            tempMessage.getHeader().setString(34, seqNum);
        }
        final String msgType = getStringField(messageString, 35);
        if (msgType != null) {
            tempMessage.getHeader().setString(35, msgType);
        }
        return tempMessage;
    }

    public static String getStringField(final String messageString, final int tag) {
        String value = null;
        final String tagString = Integer.toString(tag);
        int start = messageString.indexOf(tagString, 0);
        while (start != -1 && value == null) {
            if ((start == 0 || messageString.charAt(start - 1) == FIELD_SEPARATOR)) {
                int end = start + tagString.length();
                if ((end + 1) < messageString.length() && messageString.charAt(end) == '=') {
                    // Shouldn't this be a for look?
                	// found tag, get value
                	start = end = (end + 1);
                    for(;(end < messageString.length()
                            && messageString.charAt(end) != FIELD_SEPARATOR);
                        ++end){
                    	/* NOTE: Empty for-loop, since the loop is iterating till finding the end */
                    }

                    if (end == messageString.length()) {
                        return null;
                    } else {
                        value = messageString.substring(start, end);
                    }
                }
            }
            start = messageString.indexOf(tagString, start + 1);
        }
        return value;
    }

    private static final Map<String, String> applVerIDtoBeginString = new HashMap<String, String>() {
        {
            // No support for earlier versions of FIX
            put(ApplVerID.FIX40, FixVersions.BEGINSTRING_FIX40);
            put(ApplVerID.FIX41, FixVersions.BEGINSTRING_FIX41);
            put(ApplVerID.FIX42, FixVersions.BEGINSTRING_FIX42);
            put(ApplVerID.FIX43, FixVersions.BEGINSTRING_FIX43);
            put(ApplVerID.FIX44, FixVersions.BEGINSTRING_FIX44);
            put(ApplVerID.FIX50, FixVersions.FIX50);
            put(ApplVerID.FIX50SP1, FixVersions.FIX50SP1);
            put(ApplVerID.FIX50SP2, FixVersions.FIX50SP2);
        }
    };

    /**
     * Convert an ApplVerID to a "begin string"
     *
     * @param applVerID
     * @return the begin string for the specified ApplVerID.
     * @throws QFJException if conversion fails.
     * @see ApplVerID
     */
    public static String toBeginString(final ApplVerID applVerID) throws QFJException {
        final String beginString = applVerIDtoBeginString.get(applVerID.getValue());
        if (beginString == null) {
            throw new QFJException("Unknown or unsupported ApplVerID: " + applVerID.getValue());
        }
        return beginString;
    }

    private static final Map<String, ApplVerID> beginStringToApplVerID = new HashMap<String, ApplVerID>() {
        {
            // No support for earlier versions of FIX
            put(FixVersions.BEGINSTRING_FIX40, new ApplVerID(ApplVerID.FIX40));
            put(FixVersions.BEGINSTRING_FIX41, new ApplVerID(ApplVerID.FIX41));
            put(FixVersions.BEGINSTRING_FIX42, new ApplVerID(ApplVerID.FIX42));
            put(FixVersions.BEGINSTRING_FIX43, new ApplVerID(ApplVerID.FIX43));
            put(FixVersions.BEGINSTRING_FIX44, new ApplVerID(ApplVerID.FIX44));
            put(FixVersions.FIX50, new ApplVerID(ApplVerID.FIX50));
            put(FixVersions.FIX50SP1, new ApplVerID(ApplVerID.FIX50SP1));
            put(FixVersions.FIX50SP2, new ApplVerID(ApplVerID.FIX50SP2));
        }
    };

    /**
     * Convert a begin string to an ApplVerID
     *
     * @param beginString
     * @return the ApplVerID for the specified begin string.
     * @throws QFJException if conversion fails.
     * @see FixVersions
     */
    public static ApplVerID toApplVerID(final String beginString) throws QFJException {
        final ApplVerID applVerID = beginStringToApplVerID.get(beginString);
        if (applVerID == null) {
            throw new QFJException("Can't convert to ApplVerID: " + beginString);
        }
        return applVerID;
    }

    /**
     * Calculates the checksum for the given data.
     *
     * @param data the data to calculate the checksum on
     * @param isEntireMessage specifies whether the data is an entire message;
     *        if true, and it ends with a checksum field, that checksum
     *        field is excluded from the current checksum calculation
     * @return the calculated checksum
     */
    public static int checksum(final byte[] data, final boolean isEntireMessage) {
        int sum = 0;
        int len = data.length;
        if (isEntireMessage && data[len - 8] == '\001' && data[len - 7] == '1'
                && data[len - 6] == '0' && data[len - 5] == '=') {
			len = len - 7;
		}
        for (int i = 0; i < len; ++i) {
            sum += (data[i] & 0xFF);
        }
        return sum & 0xFF; // better than sum % 256 since it avoids overflow issues
    }

    /**
     * Calculates the checksum for the given data.
     *
     * @param charset the charset used in encoding the data
     * @param data the data to calculate the checksum on
     * @param isEntireMessage specifies whether the data is an entire message;
     *        if true, and it ends with a checksum field, that checksum
     *        field is excluded from the current checksum calculation
     * @return the calculated checksum
     */
    public static int checksum(final Charset charset, final String data, final boolean isEntireMessage) {
        if (CharsetSupport.isStringEquivalent(charset)) { // optimization - skip charset encoding
            int sum = 0;
            final int end = isEntireMessage ? data.lastIndexOf("\00110=") : -1;
            final int len = end > -1 ? end + 1 : data.length();
            for (int i = 0; i < len; ++i) {
                sum += data.charAt(i);
            }
            return sum & 0xFF; // better than sum % 256 since it avoids overflow issues
        }
        return checksum(data.getBytes(charset), isEntireMessage);
    }

    /**
     * Calculates the checksum for the given message
     * (excluding existing checksum field, if one exists).
     * The {@link CharsetSupport#setCharset global charset} is used.
     *
     * @param message the message to calculate the checksum on
     * @return the calculated checksum
     */
    public static int checksum(final String message) {
        return checksum(CharsetSupport.getCharsetInstance(), message, true);
    }

    /**
     * Calculates the length of the byte representation
     * of the given string in the given charset.
     *
     * @param charset the charset used in encoding the data
     * @param data the data to calculate the length on
     * @return the calculated length
     */
    public static int length(final Charset charset, final String data) {
        return CharsetSupport.isStringEquivalent(charset) ? data.length() : data.getBytes(charset).length;
    }
    
    /**
     * Returns an InvalidMessage Exception with optionally attached FIX message.
     *
     * @param errorMessage error description
     * @param fixMessage  problematic FIX message
     * @return InvalidMessage Exception
     */
    static InvalidMessage newInvalidMessageException(final String errorMessage, final Message fixMessage) {
        if (fixMessage != null) {
            return new InvalidMessage(errorMessage, fixMessage);
        }
        return new InvalidMessage(errorMessage);
    }
}
