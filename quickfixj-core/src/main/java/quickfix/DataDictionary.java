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

import static quickfix.FileUtil.Location.CLASSLOADER_RESOURCE;
import static quickfix.FileUtil.Location.CONTEXT_RESOURCE;
import static quickfix.FileUtil.Location.FILESYSTEM;
import static quickfix.FileUtil.Location.URL;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import quickfix.Message.Header;
import quickfix.field.BeginString;
import quickfix.field.MsgType;
import quickfix.field.SessionRejectReason;
import quickfix.field.converter.BooleanConverter;
import quickfix.field.converter.CharConverter;
import quickfix.field.converter.DoubleConverter;
import quickfix.field.converter.IntConverter;
import quickfix.field.converter.UtcDateOnlyConverter;
import quickfix.field.converter.UtcTimeOnlyConverter;
import quickfix.field.converter.UtcTimestampConverter;

/**
 * Provide the message metadata for various versions of FIX.
 */
public class DataDictionary implements Serializable {
	private static final long serialVersionUID = -5571173077860438478L;
	private static transient final String FIXT_PREFIX = "FIXT";
	private static transient final String FIX_PREFIX = "FIX";
	public static transient final String ANY_VALUE = "__ANY__";
	public static transient final String HEADER_ID = "HEADER";
	public static transient final String TRAILER_ID = "TRAILER";
	private static transient final String MESSAGE_CATEGORY_ADMIN = "admin".intern();
	private static transient final String MESSAGE_CATEGORY_APP = "app".intern();

	private static transient final int USER_DEFINED_TAG_MIN = 5000;
	private static transient final String NO = "N".intern();
	private static transient final String YES = "Y".intern();
	private boolean hasVersion = false;
	private boolean checkFieldsOutOfOrder = true;
	private boolean checkFieldsHaveValues = true;
	private boolean checkUserDefinedFields = true;
	private boolean checkUnorderedGroupFields = true;
	private boolean allowUnknownMessageFields = false;
	private String beginString;

	// CRITICAL: All of these should be changed to Eclipse Collections primitive collections
	private final Map<String, Set<Integer>> messageFields = new HashMap<>();
	private final Map<String, Set<Integer>> requiredFields = new HashMap<>();
	private final Set<String> messages = new HashSet<>();
	private final Map<String, String> messageCategory = new HashMap<>();
	private final Map<String, String> messageTypeForName = new HashMap<>();
	private final LinkedHashSet<Integer> fields = new LinkedHashSet<>();
	private final Map<Integer, FieldType> fieldTypes = new HashMap<>();
	private final Map<Integer, Set<String>> fieldValues = new HashMap<>();
	private final Map<Integer, String> fieldNames = new HashMap<>();
	private final Map<String, Integer> names = new HashMap<>();
	// NOTE: Old Veritian Code
	// private final Map<IntStringPair, String> valueNames = new HashMap<>();
	// private final Map<IntStringPair, GroupInfo> groups = new HashMap<>();
	private final IntegerStringMap<String> valueNames = new IntegerStringMap<>();
	private final StringIntegerMap<GroupInfo> groups = new StringIntegerMap<>();
	private final Map<String, Node> components = new HashMap<>();

	private DataDictionary() {
	}

	/**
	 * Initialize a data dictionary from a URL or a file path.
	 *
	 * @param location
	 *            a URL or file system path
	 * @throws ConfigError
	 */
	public DataDictionary(final String location) throws ConfigError {
		read(location);
	}

	/**
	 * Initialize a data dictionary from an input stream.
	 *
	 * @param in
	 *            the input stream
	 * @throws ConfigError
	 */
	public DataDictionary(final InputStream in) throws ConfigError {
		load(in);
	}

	/**
	 * Copy a data dictionary.
	 *
	 * @param source
	 *            the source dictionary that will be copied into this dictionary
	 */
	public DataDictionary(final DataDictionary source) {
		copyFrom(source);
	}

	private void setVersion(final String beginString) {
		this.beginString = beginString;
		hasVersion = true;
	}

	/**
	 * Get the FIX version associated with this dictionary.
	 *
	 * @return the FIX version
	 */
	public String getVersion() {
		return beginString;
	}

	private void addField(final int field) {
		fields.add(field);
	}

	private void addFieldName(final int field, final String name) throws ConfigError {
		if (names.put(name, field) != null) {
			throw new ConfigError("Field named " + name + " defined multiple times");
		}
		fieldNames.put(field, name);
	}

	/**
	 * Get the field name for a specified tag.
	 *
	 * @param field
	 *            the tag
	 * @return the field name
	 */
	public String getFieldName(final int field) {
		return fieldNames.get(field);
	}

	private void addValueName(final int field, final String value, final String name) {
		valueNames.put(field, value, name);
	}

	/**
	 * Get the value name, if any, for an enumerated field value.
	 *
	 * @param field
	 *            the tag
	 * @param value
	 *            the value
	 * @return the value's name
	 */
	public String getValueName(final int field, final String value) {
		return valueNames.get(field, value);
	}

	/**
	 * Predicate for determining if a tag is a defined field.
	 *
	 * @param field
	 *            the tag
	 * @return true if the field is defined, false otherwise
	 */
	public boolean isField(final int field) {
		return fields.contains(field);
	}

	/**
	 * Return the field type for a field.
	 *
	 * @param field
	 *            the tag
	 * @return the field type
	 */
	public FieldType getFieldType(final int field) {
		return fieldTypes.get(field);
	}

	private void addMsgType(final String msgType, final String msgName) {
		messages.add(msgType);
		if (msgName != null) {
			messageTypeForName.put(msgName, msgType);
		}
	}

	/**
	 * Return the message type for the specified name.
	 *
	 * @param msgName
	 *            The message name.
	 * @return the message type
	 */
	public String getMsgType(final String msgName) {
		return messageTypeForName.get(msgName);
	}

	/**
	 * Predicate for determining if message type is valid for a specified FIX
	 * version.
	 *
	 * @param msgType
	 *            the message type value
	 * @return true if the message type if defined, false otherwise
	 */
	public boolean isMsgType(final String msgType) {
		return messages.contains(msgType);
	}

	/**
	 * Predicate for determining if a message is in the admin category.
	 *
	 * @param msgType
	 *            the messageType
	 * @return true, if the msgType is a AdminMessage false, if the msgType is a
	 *         ApplicationMessage
	 */
	public boolean isAdminMessage(final String msgType) {
		// Categories are interned
		return MESSAGE_CATEGORY_ADMIN.equals(messageCategory.get(msgType));
	}

	/**
	 * Predicate for determining if a message is in the app category.
	 *
	 * @param msgType
	 *            the messageType
	 * @return true, if the msgType is a ApplicationMessage false, if the msgType is
	 *         a AdminMessage
	 */
	public boolean isAppMessage(final String msgType) {
		// Categories are interned
		return MESSAGE_CATEGORY_APP.equals(messageCategory.get(msgType));
	}

	private void addMsgField(final String msgType, final int field) {
		messageFields.computeIfAbsent(msgType, k -> new HashSet<>()).add(field);
	}

	/**
	 * Predicate for determining if a field is valid for a given message type.
	 *
	 * @param msgType
	 *            the message type
	 * @param field
	 *            the tag
	 * @return true if field is defined for message, false otherwise.
	 */
	public boolean isMsgField(final String msgType, final int field) {
		final Set<Integer> fields = messageFields.get(msgType);
		return fields != null && fields.contains(field);
	}

	/**
	 * Predicate for determining if field is a header field.
	 *
	 * @param field
	 *            the tag
	 * @return true if field is a header field, false otherwise.
	 */
	public boolean isHeaderField(final int field) {
		return isMsgField(HEADER_ID, field);
	}

	/**
	 * Predicate for determining if field is a trailer field.
	 *
	 * @param field
	 *            the tag
	 * @return true if field is a trailer field, false otherwise.
	 */
	public boolean isTrailerField(final int field) {
		return isMsgField(TRAILER_ID, field);
	}

	private void addFieldType(final int field, final FieldType fieldType) {
		fieldTypes.put(field, fieldType);
	}

	/**
	 * Get the field tag given a field name.
	 *
	 * @param name
	 *            the field name
	 * @return the tag
	 */
	public int getFieldTag(final String name) {
		final Integer tag = names.get(name);
		return tag != null ? tag : -1;
	}

	private void addRequiredField(final String msgType, final int field) {
		requiredFields.computeIfAbsent(msgType, k -> new HashSet<>()).add(field);
	}

	/**
	 * Predicate for determining if a field is required for a message type
	 *
	 * @param msgType
	 *            the message type
	 * @param field
	 *            the tag
	 * @return true if field is required, false otherwise
	 */
	public boolean isRequiredField(final String msgType, final int field) {
		final Set<Integer> fields = requiredFields.get(msgType);
		return fields != null && fields.contains(field);
	}

	/**
	 * Predicate for determining if a header field is a required field
	 *
	 * @param field
	 *            the tag
	 * @return true if field s required, false otherwise
	 */
	public boolean isRequiredHeaderField(final int field) {
		return isRequiredField(HEADER_ID, field);
	}

	/**
	 * Predicate for determining if a trailer field is a required field
	 *
	 * @param field
	 *            the tag
	 * @return true if field s required, false otherwise
	 */
	public boolean isRequiredTrailerField(final int field) {
		return isRequiredField(TRAILER_ID, field);
	}

	private void addFieldValue(final int field, final String value) {
		fieldValues.computeIfAbsent(field, k -> new HashSet<>()).add(value);
	}

	/**
	 * Predicate for determining if a field has enumerated values.
	 *
	 * @param field
	 *            the tag
	 * @return true if field is enumerated, false otherwise
	 */
	public boolean hasFieldValue(final int field) {
		final Set<String> values = fieldValues.get(field);
		return values != null && !values.isEmpty();
	}

	/**
	 * Predicate for determining if a field value is valid
	 *
	 * @param field
	 *            the tag
	 * @param value
	 *            a possible field value
	 * @return true if field value is valid, false otherwise
	 */
	public boolean isFieldValue(final int field, final String value) {
		final Set<String> validValues = fieldValues.get(field);

		if (validValues == null || validValues.isEmpty()) {
			return false;
		}

		if (validValues.contains(ANY_VALUE)) {
			return true;
		}

		if (!isMultipleValueStringField(field)) {
			return validValues.contains(value);
		}

		// MultipleValueString
		for (final String val : value.split(" ")) {
			if (!validValues.contains(val)) {
				return false;
			}
		}

		return true;
	}

	private void addGroup(final String msg, final int field, final int delim, final DataDictionary dataDictionary) {
		// NOTE: Old Veritian code
		// groups.put(new IntStringPair(field, msg), new GroupInfo(delim, dataDictionary));
		groups.put(msg, field, new GroupInfo(delim, dataDictionary));
	}

	/**
	 * Predicate for determining if a field is a group count field for a message
	 * type.
	 *
	 * @param msg
	 *            the message type
	 * @param field
	 *            the tag
	 * @return true if field starts a repeating group, false otherwise
	 */
	public boolean isGroup(final String msg, final int field) {
		// NOTE: Old Veritian code
		// groups.containsKey(new IntStringPair(field, msg));
		return groups.contains(msg, field);
	}

	/**
	 * Predicate for determining if a field is a header group count field
	 *
	 * @param field
	 *            the tag
	 * @return true if field starts a repeating group, false otherwise
	 */
	public boolean isHeaderGroup(final int field) {
		// NOTE: Old Veritian code
		// return groups.containsKey(new IntStringPair(field, HEADER_ID));
		return groups.contains(HEADER_ID, field);
	}

	/**
	 * Get repeating group metadata.
	 *
	 * @param msg
	 *            the message type
	 * @param field
	 *            the tag
	 * @return an object containing group-related metadata
	 */
	public GroupInfo getGroup(final String msg, final int field) {
		// NOTE: Old Veritian code
		// return groups.get(new IntStringPair(field, msg));
		return groups.get(msg, field);
	}

	/**
	 * Predicate for determining if a field is a FIX raw data field.
	 *
	 * @param field
	 *            the tag
	 * @return true if field is a raw data field, false otherwise
	 */
	public boolean isDataField(final int field) {
		return fieldTypes.get(field) == FieldType.DATA;
	}

	private boolean isMultipleValueStringField(final int field) {
		final FieldType fieldType = fieldTypes.get(field);
		return fieldType == FieldType.MULTIPLEVALUESTRING || fieldType == FieldType.MULTIPLESTRINGVALUE;
	}

	/**
	 * Controls whether out of order fields are checked.
	 *
	 * @param flag
	 *            true = checked, false = not checked
	 */
	public void setCheckFieldsOutOfOrder(final boolean flag) {
		checkFieldsOutOfOrder = flag;
	}

	public boolean isCheckFieldsOutOfOrder() {
		return checkFieldsOutOfOrder;
	}

	public boolean isCheckUnorderedGroupFields() {
		return checkUnorderedGroupFields;
	}

	public boolean isCheckFieldsHaveValues() {
		return checkFieldsHaveValues;
	}

	public boolean isCheckUserDefinedFields() {
		return checkUserDefinedFields;
	}

	public boolean isAllowUnknownMessageFields() {
		return allowUnknownMessageFields;
	}

	/**
	 * Controls whether group fields are in the same order
	 *
	 * @param flag
	 *            true = checked, false = not checked
	 */
	public void setCheckUnorderedGroupFields(final boolean flag) {
		checkUnorderedGroupFields = flag;
		for (final Map<Integer, GroupInfo> gm : groups.values()) {
			for (final GroupInfo gi : gm.values()) {
				gi.getDataDictionary().setCheckUnorderedGroupFields(flag);
			}
		}
	}

	/**
	 * Controls whether empty field values are checked.
	 *
	 * @param flag
	 *            true = checked, false = not checked
	 */
	public void setCheckFieldsHaveValues(final boolean flag) {
		checkFieldsHaveValues = flag;
		for (final Map<Integer, GroupInfo> gm : groups.values()) {
			for (final GroupInfo gi : gm.values()) {
				gi.getDataDictionary().setCheckFieldsHaveValues(flag);
			}
		}
	}

	/**
	 * Controls whether user defined fields are checked.
	 *
	 * @param flag
	 *            true = checked, false = not checked
	 */
	public void setCheckUserDefinedFields(final boolean flag) {
		checkUserDefinedFields = flag;
		for (final Map<Integer, GroupInfo> gm : groups.values()) {
			for (final GroupInfo gi : gm.values()) {
				gi.getDataDictionary().setCheckUserDefinedFields(flag);
			}
		}
	}

	public void setAllowUnknownMessageFields(final boolean allowUnknownFields) {
		allowUnknownMessageFields = allowUnknownFields;
		for (final Map<Integer, GroupInfo> gm : groups.values()) {
			for (final GroupInfo gi : gm.values()) {
				gi.getDataDictionary().setAllowUnknownMessageFields(allowUnknownFields);
			}
		}
	}

	private void copyFrom(final DataDictionary rhs) {
		hasVersion = rhs.hasVersion;
		beginString = rhs.beginString;
		checkFieldsOutOfOrder = rhs.checkFieldsOutOfOrder;
		checkFieldsHaveValues = rhs.checkFieldsHaveValues;
		checkUserDefinedFields = rhs.checkUserDefinedFields;

		copyMap(messageFields, rhs.messageFields);
		copyMap(requiredFields, rhs.requiredFields);
		copyCollection(messages, rhs.messages);
		copyCollection(fields, rhs.fields);
		copyMap(fieldTypes, rhs.fieldTypes);
		copyMap(fieldValues, rhs.fieldValues);
		copyMap(fieldNames, rhs.fieldNames);
		copyMap(names, rhs.names);
		copyMap(valueNames, rhs.valueNames);
		copyGroups(groups, rhs.groups);
		copyMap(components, rhs.components);

		setCheckFieldsOutOfOrder(rhs.checkFieldsOutOfOrder);
		setCheckFieldsHaveValues(rhs.checkFieldsHaveValues);
		setCheckUserDefinedFields(rhs.checkUserDefinedFields);
		setCheckUnorderedGroupFields(rhs.checkUnorderedGroupFields);
		setAllowUnknownMessageFields(rhs.allowUnknownMessageFields);
	}

	@SuppressWarnings("unchecked")
	private static <K, V> void copyMap(final Map<K, V> lhs, final Map<K, V> rhs) {
		lhs.clear();
		for (final Map.Entry<K, V> entry : rhs.entrySet()) {
			Object value = entry.getValue();
			if (value instanceof Collection) {
				Collection<V> copy;
				try {
					copy = (Collection<V>) value.getClass().getDeclaredConstructor().newInstance();
				} catch (final RuntimeException e) {
					throw e;
				} catch (final Exception e) {
					throw new RuntimeException(e);
				}
				copyCollection(copy, (Collection<V>) value);
				value = copy;
			}
			lhs.put(entry.getKey(), (V) value);
		}
	}

	/**
	 * copy groups including their data dictionaries and validation settings
	 *
	 * @param lhs
	 *            target
	 * @param rhs
	 *            source
	 */
	private static void copyGroups(final StringIntegerMap<GroupInfo> lhs, final StringIntegerMap<GroupInfo> rhs) {
		lhs.clear();
		for (final Map.Entry<String, Map<Integer, GroupInfo>> outer : rhs.entrySet()) {
			for (final Map.Entry<Integer, GroupInfo> entry : outer.getValue().entrySet()) {
				final GroupInfo value = new GroupInfo(entry.getValue().getDelimiterField(), new DataDictionary(entry.getValue().getDataDictionary()));
				lhs.put(outer.getKey(), entry.getKey(), value);
			}
		}
	}

	private static <V> void copyCollection(final Collection<V> lhs, final Collection<V> rhs) {
		lhs.clear();
		lhs.addAll(rhs);
	}

	/**
	 * Validate a message, including the header and trailer fields.
	 *
	 * @param message
	 *            the message
	 * @throws IncorrectTagValue
	 *             if a field value is not valid
	 * @throws FieldNotFound
	 *             if a field cannot be found
	 * @throws IncorrectDataFormat
	 *             if a field value has a wrong data type
	 */
	public void validate(final Message message) throws IncorrectTagValue, FieldNotFound, IncorrectDataFormat {
		validate(message, false);
	}

	/**
	 * Validate the message body, with header and trailer fields being validated
	 * conditionally.
	 *
	 * @param message
	 *            the message
	 * @param bodyOnly
	 *            whether to validate just the message body, or to validate the
	 *            header and trailer sections as well.
	 * @throws IncorrectTagValue
	 *             if a field value is not valid
	 * @throws FieldNotFound
	 *             if a field cannot be found
	 * @throws IncorrectDataFormat
	 *             if a field value has a wrong data type
	 */
	public void validate(final Message message, final boolean bodyOnly) throws IncorrectTagValue, FieldNotFound, IncorrectDataFormat {
		validate(message, bodyOnly ? null : this, this);
	}

	static void validate(final Message message, final DataDictionary sessionDataDictionary, final DataDictionary applicationDataDictionary)
			throws IncorrectTagValue, FieldNotFound, IncorrectDataFormat {
		final boolean bodyOnly = sessionDataDictionary == null;

		final Header header = message.getHeader();
		if (isVersionSpecified(sessionDataDictionary)
				&& !sessionDataDictionary.getVersion().equals(header.getString(BeginString.FIELD))
				&& !"FIXT.1.1".equals(header.getString(BeginString.FIELD))
				&& !"FIX.5.0".equals(sessionDataDictionary.getVersion())) {
			throw new UnsupportedVersion("Message version '" + header.getString(BeginString.FIELD)
			+ "' does not match the data dictionary version '" + sessionDataDictionary.getVersion() + '\'');
		}

		if (!message.hasValidStructure() && message.getException() != null) {
			throw message.getException();
		}

		final String msgType = header.getString(MsgType.FIELD);
		if (isVersionSpecified(applicationDataDictionary)) {
			applicationDataDictionary.checkMsgType(msgType);
			applicationDataDictionary.checkHasRequired(header, message, message.getTrailer(), msgType, bodyOnly);
		}

		if (!bodyOnly) {
			sessionDataDictionary.iterate(header, HEADER_ID, sessionDataDictionary);
			sessionDataDictionary.iterate(message.getTrailer(), TRAILER_ID, sessionDataDictionary);
		}

		applicationDataDictionary.iterate(message, msgType, applicationDataDictionary);
	}

	private static boolean isVersionSpecified(final DataDictionary dd) {
		return dd != null && dd.hasVersion;
	}

	private void iterate(final FieldMap map, final String msgType, final DataDictionary dd) throws IncorrectTagValue, IncorrectDataFormat {
		final Iterator<Field<?>> iterator = map.iterator();
		while (iterator.hasNext()) {
			final StringField field = (StringField) iterator.next();

			checkHasValue(field);

			if (hasVersion) {
				checkValidFormat(field);
				checkValue(field);
			}

			// NOTE: Veritian changes
			if (beginString != null) { // && shouldCheckTag(field)) {
				// dd.checkValidTagNumber(field);
				final boolean isMapInstanceOfMessage = map instanceof Message;
				// NOTE: Old Veritian cod that moes the checkField call into the if statement
				//				if(isMapInstanceOfMessage) {
				//					dd.checkField(field, msgType, map instanceof Message);
				//				}
				dd.checkField(field, msgType, isMapInstanceOfMessage);
				dd.checkGroupCount(field, map, msgType);
			}
		}

		for (final List<Group> groups : map.getGroups().values()) {
			for (final Group group : groups) {
				iterate(group, msgType, dd.getGroup(msgType, group.getFieldTag()).getDataDictionary());
			}
		}
	}

	/** Check if message type is defined in spec. **/
	private void checkMsgType(final String msgType) {
		if (!isMsgType(msgType)) {
			throw new FieldException(SessionRejectReason.INVALID_MSGTYPE, MsgType.FIELD);
		}
	}

	// / If we need to check for the tag in the dictionary
	private boolean shouldCheckTag(final Field<?> field) {
		return !(!checkUserDefinedFields && field.getField() >= USER_DEFINED_TAG_MIN);
	}

	/** Check if field tag number is defined in the specification. **/
	void checkValidTagNumber(final Field<?> field) {
		if (!fields.contains(field.getTag())) {
			throw new FieldException(SessionRejectReason.INVALID_TAG_NUMBER, field.getField());
		}
	}

	/** Check if the field tag is defined for message or group **/
	void checkField(final Field<?> field, final String msgType, final boolean message) {
		// use different validation for groups and messages
		final int fieldValue = field.getField();
		final boolean messageField = message ? isMsgField(msgType, fieldValue) : fields.contains(fieldValue);
		final boolean fail = checkFieldFailure(fieldValue, messageField);

		if (fail) {
			if (fields.contains(fieldValue)) {
				throw new FieldException(SessionRejectReason.TAG_NOT_DEFINED_FOR_THIS_MESSAGE_TYPE, fieldValue);
			}
			throw new FieldException(SessionRejectReason.INVALID_TAG_NUMBER, fieldValue);
		}
	}

	boolean checkFieldFailure(final int field, final boolean messageField) {
		boolean fail;
		if (field < USER_DEFINED_TAG_MIN) {
			fail = !messageField && !allowUnknownMessageFields;
		} else {
			fail = !messageField && checkUserDefinedFields;
		}
		return fail;
	}

	private void checkValidFormat(final StringField field) throws IncorrectDataFormat {
		final FieldType fieldType = getFieldType(field.getTag());
		if (fieldType == null) {
			return;
		}
		if (!checkFieldsHaveValues && field.getValue().length() == 0) {
			return;
		}
		try {
			switch (fieldType) {
				case STRING:
				case MULTIPLEVALUESTRING:
				case MULTIPLESTRINGVALUE:
				case EXCHANGE:
				case LOCALMKTDATE:
				case DATA:
				case MONTHYEAR:
				case DAYOFMONTH:
				case COUNTRY:
					// String
					break;
				case INT:
				case NUMINGROUP:
				case SEQNUM:
				case LENGTH:
					IntConverter.convert(field.getValue());
					break;
				case PRICE:
				case AMT:
				case QTY:
				case FLOAT:
				case PRICEOFFSET:
				case PERCENTAGE:
					DoubleConverter.convert(field.getValue());
					break;
				case BOOLEAN:
					BooleanConverter.convert(field.getValue());
					break;
				case UTCDATE:
					UtcDateOnlyConverter.convert(field.getValue());
					break;
				case UTCTIMEONLY:
					UtcTimeOnlyConverter.convert(field.getValue());
					break;
				case UTCTIMESTAMP:
				case TIME:
					UtcTimestampConverter.convert(field.getValue());
					break;
				case CHAR:
					if (beginString.compareTo(FixVersions.BEGINSTRING_FIX41) > 0) {
						CharConverter.convert(field.getValue());
					} // otherwise it's a String, for older FIX versions
					break;
			}
		} catch (final FieldConvertError e) {
			throw new IncorrectDataFormat(field.getTag(), field.getValue());
		}
	}

	private void checkValue(final StringField field) throws IncorrectTagValue {
		final int tag = field.getField();
		if (hasFieldValue(tag) && !isFieldValue(tag, field.getValue())) {
			throw new IncorrectTagValue(tag);
		}
	}

	/** Check if a field has a value. **/
	private void checkHasValue(final StringField field) {
		if (checkFieldsHaveValues && field.getValue().length() == 0) {
			throw new FieldException(SessionRejectReason.TAG_SPECIFIED_WITHOUT_A_VALUE, field.getField());
		}
	}

	// / Check if a field is in this message type.
	private void checkIsInMessage(final Field<?> field, final String msgType) {
		final int fieldNum = field.getField();
		if (!isMsgField(msgType, fieldNum) && !allowUnknownMessageFields) {
			throw new FieldException(SessionRejectReason.TAG_NOT_DEFINED_FOR_THIS_MESSAGE_TYPE,
					fieldNum);
		}
	}

	/** Check if group count matches number of groups in **/
	private void checkGroupCount(final StringField field, final FieldMap fieldMap, final String msgType) {
		final int fieldNum = field.getField();
		if (isGroup(msgType, fieldNum)) {
			if (fieldMap.getGroupCount(fieldNum) != Integer.parseInt(field.getValue())) {
				throw new FieldException(SessionRejectReason.INCORRECT_NUMINGROUP_COUNT_FOR_REPEATING_GROUP, fieldNum);
			}
		}
	}

	/** Check if a message has all required fields. **/
	void checkHasRequired(final FieldMap header, final FieldMap body, final FieldMap trailer, final String msgType, final boolean bodyOnly) {
		if (!bodyOnly) {
			checkHasRequired(HEADER_ID, header, bodyOnly);
			checkHasRequired(TRAILER_ID, trailer, bodyOnly);
		}

		checkHasRequired(msgType, body, bodyOnly);
	}

	private void checkHasRequired(final String msgType, final FieldMap fields, final boolean bodyOnly) {
		final Set<Integer> requiredFieldsForMessage = requiredFields.get(msgType);
		if (requiredFieldsForMessage == null || requiredFieldsForMessage.isEmpty()) {
			return;
		}

		for (final int field : requiredFieldsForMessage) {
			if (!fields.isSetField(field)) {
				throw new FieldException(SessionRejectReason.REQUIRED_TAG_MISSING, field);
			}
		}

		final Map<Integer, List<Group>> groups = fields.getGroups();
		if (!groups.isEmpty()) {
			for (final Map.Entry<Integer, List<Group>> entry : groups.entrySet()) {
				final GroupInfo p = getGroup(msgType, entry.getKey());
				if (p == null) { continue; }

				for (final Group groupInstance : entry.getValue()) {
					p.getDataDictionary().checkHasRequired(groupInstance, groupInstance, groupInstance, msgType, bodyOnly);
				}
			}
		}
	}

	private int countElementNodes(final NodeList nodes) {
		int elementNodesCount = 0;

		for (int i = 0; i < nodes.getLength(); ++i) {
			if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) {
				++elementNodesCount;
			}
		}

		return elementNodesCount;
	}

	private void read(final String location) throws ConfigError {
		final InputStream inputStream = FileUtil.open(getClass(), location, URL, FILESYSTEM, CONTEXT_RESOURCE, CLASSLOADER_RESOURCE);
		if (inputStream == null) {
			throw new ConfigError("Could not find data dictionary: " + location);
		}

		try {
			load(inputStream);
		} catch (final Exception e) {
			throw new ConfigError(location + ": " + e.getMessage(), e);
		} finally {
			try {
				inputStream.close();
			} catch (final IOException e) {
				throw new ConfigError(e);
			}
		}
	}

	private void load(final InputStream inputStream) throws ConfigError {
		final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		Document document;
		try {
			final DocumentBuilder builder = factory.newDocumentBuilder();
			document = builder.parse(inputStream);
		} catch (final Throwable e) {
			throw new ConfigError("Could not parse data dictionary file", e);
		}

		final Element documentElement = document.getDocumentElement();
		if (!documentElement.getNodeName().equals("fix")) {
			throw new ConfigError("Could not parse data dictionary file, or no <fix> node found at root");
		}

		if (!documentElement.hasAttribute("major")) {
			throw new ConfigError("major attribute not found on <fix>");
		}

		if (!documentElement.hasAttribute("minor")) {
			throw new ConfigError("minor attribute not found on <fix>");
		}

		final String dictionaryType = documentElement.hasAttribute("type") ? documentElement.getAttribute("type") : FIX_PREFIX;

		setVersion(dictionaryType + '.' + documentElement.getAttribute("major") + '.' + documentElement.getAttribute("minor"));

		// Index Components
		final NodeList componentsNode = documentElement.getElementsByTagName("components");
		final int componentsNodeLength = componentsNode.getLength();
		if (componentsNodeLength > 0) {
			final NodeList componentNodes = componentsNode.item(0).getChildNodes();
			for (int i = 0; i < componentsNodeLength; ++i) {
				final Node componentNode = componentNodes.item(i);
				if (!"component".equals(componentNode.getNodeName())) { continue; }

				final String name = getAttribute(componentNode, "name");
				if (name == null) {
					throw new ConfigError("<component> does not have a name attribute");
				}
				components.put(name, componentNode);
			}
		}

		// FIELDS
		final NodeList fieldsNode = documentElement.getElementsByTagName("fields");
		if (fieldsNode.getLength() == 0) {
			throw new ConfigError("<fields> section not found in data dictionary");
		}

		final NodeList fieldNodes = fieldsNode.item(0).getChildNodes();
		if (countElementNodes(fieldNodes) == 0) {
			throw new ConfigError("No fields defined");
		}

		final int fieldNodesLength = fieldNodes.getLength();
		for (int i = 0; i < fieldNodesLength; ++i) {
			final Node fieldNode = fieldNodes.item(i);
			if ("field".equals(fieldNode.getNodeName())) {
				final String name = getAttribute(fieldNode, "name");
				if (name == null) {
					throw new ConfigError("<field> does not have a name attribute");
				}

				final String number = getAttribute(fieldNode, "number");
				if (number == null) {
					throw new ConfigError("<field> " + name + " does not have a number attribute");
				}

				final int num = Integer.parseInt(number);

				final String type = getAttribute(fieldNode, "type");
				if (type == null) {
					throw new ConfigError("<field> " + name + " does not have a type attribute");
				}

				addField(num);
				addFieldType(num, FieldType.fromName(getVersion(), type));
				addFieldName(num, name);

				final NodeList valueNodes = fieldNode.getChildNodes();
				for (int j = 0; j < valueNodes.getLength(); ++j) {
					final Node valueNode = valueNodes.item(j);
					if (!"value".equals(valueNode.getNodeName())) { continue; }

					final String enumeration = getAttribute(valueNode, "enum");
					if (enumeration == null) {
						throw new ConfigError("<value> does not have enum attribute in field " + name);
					}
					addFieldValue(num, enumeration);
					final String description = getAttribute(valueNode, "description");
					if (description != null) {
						addValueName(num, enumeration, description);
					}
				}

				if (fieldValues.containsKey(num)) {
					final String allowOtherValues = getAttribute(fieldNode, "allowOtherValues");
					if (Boolean.parseBoolean(allowOtherValues)) {
						addFieldValue(num, ANY_VALUE);
					}
				}
			}
		}

		if (beginString.startsWith(FIXT_PREFIX) || beginString.compareTo(FixVersions.FIX50) < 0) {
			// HEADER
			final NodeList headerNode = documentElement.getElementsByTagName("header");
			if (headerNode.getLength() == 0) {
				throw new ConfigError("<header> section not found in data dictionary");
			}

			load(document, HEADER_ID, headerNode.item(0));

			// TRAILER
			final NodeList trailerNode = documentElement.getElementsByTagName("trailer");
			if (trailerNode.getLength() == 0) {
				throw new ConfigError("<trailer> section not found in data dictionary");
			}

			load(document, TRAILER_ID, trailerNode.item(0));
		}

		// MSGTYPE
		final NodeList messagesNode = documentElement.getElementsByTagName("messages");
		if (messagesNode.getLength() == 0) {
			throw new ConfigError("<messages> section not found in data dictionary");
		}

		final NodeList messageNodes = messagesNode.item(0).getChildNodes();
		if (countElementNodes(messageNodes) == 0) {
			throw new ConfigError("No messages defined");
		}

		final int messageNodesLength = messageNodes.getLength();
		for (int i = 0; i < messageNodesLength; ++i) {
			final Node messageNode = messageNodes.item(i);
			if (!"message".equals(messageNode.getNodeName())) { continue; }

			final String msgtype = getAttribute(messageNode, "msgtype");
			if (msgtype == null) {
				throw new ConfigError("<message> does not have a msgtype attribute");
			}

			final String msgcat = getAttribute(messageNode, "msgcat");
			if (msgcat != null) {
				messageCategory.put(msgtype, msgcat.intern());
			}

			final String name = getAttribute(messageNode, "name");
			addMsgType(msgtype, name);

			if (name != null) {
				addValueName(MsgType.FIELD, msgtype, name);
			}

			load(document, msgtype, messageNode);
		}
	}

	public int getNumMessageCategories() {
		return messageCategory.size();
	}

	private void load(final Document document, final String msgtype, final Node node) throws ConfigError {
		String name;
		final NodeList fieldNodes = node.getChildNodes();
		if (countElementNodes(fieldNodes) == 0) {
			throw new ConfigError("No fields found: msgType=" + msgtype);
		}

		final int fieldNodesLength = fieldNodes.getLength();
		for (int j = 0; j < fieldNodesLength; ++j) {
			final Node fieldNode = fieldNodes.item(j);

			final String nodeName = fieldNode.getNodeName();
			final boolean isNodeNameEqualGroup = "group".equals(nodeName);
			if ("field".equals(nodeName) || isNodeNameEqualGroup) {
				name = getAttribute(fieldNode, "name");
				if (name == null) {
					throw new ConfigError("<field> does not have a name attribute");
				}

				final int num = lookupXMLFieldNumber(document, name);
				addMsgField(msgtype, num);

				final String required = getAttribute(fieldNode, "required", NO);
				if (required == null) {
					throw new ConfigError('<' + fieldNode.getNodeName() + "> does not have a 'required' attribute");
				}
				final boolean isRequiredSetAsY = YES.equalsIgnoreCase(required);
				if (isRequiredSetAsY) {
					addRequiredField(msgtype, num);
				}
				// NOTE: This is the old Veritian code for handling a group that was added in.
				/* if (isNodeNameEqualGroup) {
					addXMLGroup(document, fieldNode, msgtype, this, isRequiredSetAsY);
				} */
			} else if ("component".equals(nodeName)) {

				final String required = getAttribute(fieldNode, "required");
				if (required == null) {
					throw new ConfigError("<component> does not have a 'required' attribute");
				}
				addXMLComponentFields(document, fieldNode, msgtype, this, YES.equalsIgnoreCase(required));
			}

			// NOTE: this is the new QuickFIXJ code for handling the group.
			if (isNodeNameEqualGroup) {
				final String required = getAttribute(fieldNode, "required");
				if (required == null) {
					throw new ConfigError("<group> does not have a 'required' attribute");
				}
				addXMLGroup(document, fieldNode, msgtype, this, YES.equalsIgnoreCase(required));
			}
		}
	}

	private int[] orderedFieldsArray;

	public int[] getOrderedFields() {
		if (orderedFieldsArray == null) {
			orderedFieldsArray = new int[fields.size()];
			int i = 0;
			for (final Integer field : fields) {
				orderedFieldsArray[i++] = field;
			}
		}

		return orderedFieldsArray;
	}

	private int lookupXMLFieldNumber(final Document document, final Node node) throws ConfigError {
		final Element element = (Element) node;
		if (!element.hasAttribute("name")) {
			throw new ConfigError("No name given to field");
		}
		return lookupXMLFieldNumber(document, element.getAttribute("name"));
	}

	private int lookupXMLFieldNumber(final Document document, final String name) throws ConfigError {
		final Integer fieldNumber = names.get(name);
		if (fieldNumber == null) {
			throw new ConfigError("Field " + name + " not defined in fields section");
		}
		return fieldNumber;
	}

	private int addXMLComponentFields(final Document document, final Node node, final String msgtype, final DataDictionary dd, final boolean componentRequired) throws ConfigError {
		int firstField = 0;

		String name = getAttribute(node, "name");
		if (name == null) {
			throw new ConfigError("No name given to component");
		}

		final Node componentNode = components.get(name);
		if (componentNode == null) {
			throw new ConfigError("Component " + name + " not found");
		}

		final NodeList componentFieldNodes = componentNode.getChildNodes();
		final int componentFieldNodesLength = componentFieldNodes.getLength();
		for (int i = 0; i < componentFieldNodesLength; ++i) {
			final Node componentFieldNode = componentFieldNodes.item(i);

			final String componentFieldNodeName = componentFieldNode.getNodeName();
			final boolean isComponentFieldNodeNameEqualGroup = "group".equals(componentFieldNodeName);

			if ("field".equals(componentFieldNodeName) || isComponentFieldNodeNameEqualGroup) {
				name = getAttribute(componentFieldNode, "name");
				if (name == null) {
					throw new ConfigError("No name given to field");
				}

				final int field = lookupXMLFieldNumber(document, name);
				if (firstField == 0) {
					firstField = field;
				}

				final String required = getAttribute(componentFieldNode, "required");
				final boolean isRequired = YES.equalsIgnoreCase(required);
				if (isRequired && componentRequired) {
					dd.addRequiredField(msgtype, field);
				}

				dd.addField(field);
				dd.addMsgField(msgtype, field);

				// NOTE: This is Veritian's old mechanism for handling a group
				/* if (isComponentFieldNodeNameEqualGroup) {
					addXMLGroup(document, componentFieldNode, msgtype, dd, isRequired);
				} */
			}
			if (isComponentFieldNodeNameEqualGroup) {
				final String required = getAttribute(componentFieldNode, "required");
				final boolean isRequired = YES.equalsIgnoreCase(required);
				addXMLGroup(document, componentFieldNode, msgtype, dd, isRequired);
			}

			if ("component".equals(componentFieldNodeName)) {
				final String required = getAttribute(componentFieldNode, "required");
				final boolean isRequired = YES.equalsIgnoreCase(required);
				addXMLComponentFields(document, componentFieldNode, msgtype, dd, isRequired);
			}
		}
		return firstField;
	}

	private void addXMLGroup(final Document document, final Node node, final String msgtype, final DataDictionary dd, final boolean groupRequired) throws ConfigError {
		final String name = getAttribute(node, "name");
		if (name == null) {
			throw new ConfigError("No name given to group");
		}
		final int group = lookupXMLFieldNumber(document, name);
		int delim = 0;
		int field = 0;
		final DataDictionary groupDD = new DataDictionary();
		groupDD.setVersion(dd.getVersion());
		final NodeList fieldNodeList = node.getChildNodes();
		final int fieldNoteListLength = fieldNodeList.getLength();
		for (int i = 0; i < fieldNoteListLength; ++i) {
			final Node fieldNode = fieldNodeList.item(i);
			final String nodeName = fieldNode.getNodeName();
			if ("field".equals(nodeName)) {
				field = lookupXMLFieldNumber(document, fieldNode);
				groupDD.addField(field);
				final String required = getAttribute(fieldNode, "required");
				if (required != null && YES.equalsIgnoreCase(required) && groupRequired) {
					groupDD.addRequiredField(msgtype, field);
				}
			} else if ("component".equals(nodeName)) {
				final String required = getAttribute(fieldNode, "required");
				final boolean isRequired = required != null && YES.equalsIgnoreCase(required);
				field = addXMLComponentFields(document, fieldNode, msgtype, groupDD, isRequired);
			} else if ("group".equals(nodeName)) {
				field = lookupXMLFieldNumber(document, fieldNode);
				groupDD.addField(field);
				final String required = getAttribute(fieldNode, "required");
				if (required != null && YES.equalsIgnoreCase(required) && groupRequired) {
					groupDD.addRequiredField(msgtype, field);
				}
				final boolean isRequired = required != null && YES.equalsIgnoreCase(required);
				addXMLGroup(document, fieldNode, msgtype, groupDD, isRequired);
			}
			if (delim == 0) {
				delim = field;
			}
		}

		if (delim != 0) {
			dd.addGroup(msgtype, group, delim, groupDD);
		}
	}

	private String getAttribute(final Node node, final String name) {
		return getAttribute(node, name, null);
	}

	private String getAttribute(final Node node, final String name, final String defaultValue) {
		final NamedNodeMap attributes = node.getAttributes();
		if (attributes != null) {
			final Node namedItem = attributes.getNamedItem(name);
			return namedItem != null ? namedItem.getNodeValue() : null;
		}
		return defaultValue;
	}

	private static final class IntStringPair implements Serializable {
		private static final long serialVersionUID = -5997544378315245511L;
		private final int intValue;

		private final String stringValue;

		public IntStringPair(final int value, final String value2) {
			intValue = value;
			stringValue = value2;
		}

		@Override
		public boolean equals(final Object other) {
			return this == other
					|| other instanceof IntStringPair
					&& intValue == ((IntStringPair) other).intValue
					&& stringValue.equals(((IntStringPair) other).stringValue);
		}

		@Override
		public int hashCode() {
			return stringValue.hashCode() + intValue;
		}

		/**
		 * For debugging
		 */
		@Override
		public String toString() {
			return '(' + intValue + ',' + stringValue + ')';
		}
	}

	private static class StringIntegerMap<V> extends HashMap<String, Map<Integer, V>> {

		public boolean contains(final String group, final int field) {
			final Map<Integer, V> map = get(group);
			return map != null && map.containsKey(field);
		}

		public V get(final String group, final int field) {
			final Map<Integer, V> map = get(group);
			return map == null ? null : map.get(field);
		}

		public void put(final String group, final int field, final V value) {
			computeIfAbsent(group, __ -> new HashMap<>()).put(field, value);
		}

	}

	private static class IntegerStringMap<V> extends HashMap<Integer, Map<String, V>> {

		public boolean contains(final int field, final String group) {
			final Map<String, V> map = get(field);
			return map != null && map.containsKey(group);
		}

		public V get(final int field, final String group) {
			final Map<String, V> map = get(field);
			return map == null ? null : map.get(group);
		}

		public void put(final int field, final String group, final V value) {
			computeIfAbsent(field, __ -> new HashMap<>()).put(group, value);
		}

	}

	/**
	 * Contains meta-data for FIX repeating groups
	 */
	public static final class GroupInfo implements Serializable {
		private static final long serialVersionUID = -4961962718729839203L;

		private final int delimiterField;

		private final DataDictionary dataDictionary;

		private GroupInfo(final int field, final DataDictionary dictionary) {
			delimiterField = field;
			dataDictionary = dictionary;
		}

		public DataDictionary getDataDictionary() {
			return dataDictionary;
		}

		/**
		 * Returns the delimiter field used to start a repeating group instance.
		 *
		 * @return delimiter field
		 */
		public int getDelimiterField() {
			return delimiterField;
		}

		@Override
		public boolean equals(final Object other) {
			return this == other || other instanceof GroupInfo
					&& delimiterField == ((GroupInfo) other).delimiterField
					&& dataDictionary.equals(((GroupInfo) other).dataDictionary);
		}

		@Override
		public int hashCode() {
			return delimiterField;
		}
	}

}
