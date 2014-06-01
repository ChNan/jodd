// Copyright (c) 2003-2014, Jodd Team (jodd.org). All Rights Reserved.

package jodd.lagarto;

import jodd.util.CharUtil;
import jodd.util.StringPool;
import jodd.util.UnsafeUtil;

import java.nio.CharBuffer;

import static jodd.util.CharUtil.equalsOne;
import static jodd.util.CharUtil.isAlpha;

/**
 * HTML/XML content parser using {@link TagVisitor} for callbacks.
 * Differences from: http://www.w3.org/TR/html5/
 * <ul>
 * <li>no {@code &} parsing in DATA_STATE.
 * <li>tag name case (and other entities) is not changed
 * </ul>
 *
 * What should be changed in SPEC:
 * <ul>
 * <li>TOKENIZER is the one who should deal with all state changes,
 * 		not the tree builder!</li>
 * <li>Recognize two type of states, one that iterates and one that
 * doesn't</li>
 * <li>Order of error/state change must be always the same.</li>
 * <li>TOKENIZER should NOT change the tag names letter case.
 * Tokenizer should not change the source in any way.</li>
 * </ul>
 */
public class LagartoParser2 extends CharScanner {

	protected TagVisitor visitor;
	protected LagartoParserContext ctx;
	protected ParsedTag tag;
	protected ParsedDoctype doctype;

	/**
	 * Creates parser on char array.
	 */
	public LagartoParser2(char[] charArray) {
			initialize(charArray);
		}

	/**
	 * Creates parser on a String.
	 */
	public LagartoParser2(String string) {
			initialize(UnsafeUtil.getChars(string));
		}

	/**
	 * Initializes parser.
	 */
	protected void initialize(char[] input) {
		super.initialize(input);
		this.textStartNdx = textEndNdx = -1;
		this.ctx = new LagartoParserContext();
		this.tag = new ParsedTag(input);
		this.doctype = new ParsedDoctype(input);
		this.text = new char[1024];
		this.textLen = 0;
	}

	// ---------------------------------------------------------------- properties

	protected boolean xmlMode = false;
	protected boolean enableConditionalComments = true;
	protected boolean calculatePosition;

	public boolean isEnableConditionalComments() {
		return enableConditionalComments;
	}

	/**
	 * Enables detection of IE conditional comments. If not enabled,
	 * downlevel-hidden cond. comments will be treated as regular comment,
	 * while revealed cond. comments will be treated as an error.
	 */
	public void setEnableConditionalComments(boolean enableConditionalComments) {
		this.enableConditionalComments = enableConditionalComments;
	}

	/**
	 * Returns <code>true</code> if XML mode is enabled.
	 */
	public boolean isXmlMode() {
		return xmlMode;
	}

	/**
	 * Enables XML mode when some XML-only events.
	 */
	public void setXmlMode(boolean xmlMode) {
		this.xmlMode = xmlMode;
	}

	/**
	 * Resolves current position on {@link #error(String)} parsing errors}
	 * and for DOM elements. Note: this makes processing SLOW!
	 * JFlex may be used to track current line and row, but that brings
	 * overhead, and can't be easily disabled. By enabling this property,
	 * position will be calculated manually only on errors.
	 */
	public void setCalculatePosition(boolean calculatePosition) {
		this.calculatePosition = calculatePosition;
	}

	public boolean isCalculatePosition() {
		return calculatePosition;
	}

	// ---------------------------------------------------------------- parse

	protected boolean parsing;

	/**
	 * Parses content and callback provided {@link TagVisitor}.
	 */
	public void parse(TagVisitor visitor) {
		this.visitor = visitor;

		visitor.start(ctx);

		parsing = true;

		while (parsing) {
			state.parse();
		}

		flushText();

		visitor.end();
	}

	// ---------------------------------------------------------------- start & end

	protected char[] text;
	protected int textLen;

	/**
	 * Data state.
	 */
	protected State DATA_STATE =  new State() {
		public void parse() {

			while (true) {
				ndx++;

				if (isEOF()) {
					parsing = false;
					return;
				}

				char c = input[ndx];

				if (c == '<') {
					state = TAG_OPEN;
					return;
				}

				if (c == '&') {
					// todo
				}

				emitChar(c);
			}
		}
	};

	/**
	 * Emits characters into the local buffer.
	 * Text will be emitted only on {@link #flushText()}.
	 */
	protected void emitChar(char c) {
		if (textLen == text.length) {
			// todo resize array
		}
		text[textLen] = c;
		textLen++;
	}

	protected State TAG_OPEN = new State() {
		public void parse() {
			tag.start(ndx);

			ndx++;

			if (isEOF()) {
				errorEOF();
				state = DATA_STATE;
				emitChar('<');
				return;
			}

			char c = input[ndx];

			if (c == '!') {
				state = MARKUP_DECLARATION_OPEN;
				return;
			}
			if (c == '/') {
				state = END_TAG_OPEN;
				return;
			}
			if (isAlpha(c)) {
				state = TAG_NAME;
				return;
			}
			if (c == '?') {
				errorInvalidToken();
				state = BOGUS_COMMENT;
				return;
			}

			errorInvalidToken();
			state = DATA_STATE;
			emitChar('<');

			ndx--;
		}
	};

	protected State END_TAG_OPEN = new State() {
		public void parse() {
			ndx++;

			if (isEOF()) {
				errorEOF();
				state = DATA_STATE;
				return;
			}

			char c = input[ndx];

			if (isAlpha(c)) {
				tag.setType(TagType.END);
				state = TAG_NAME;
				return;
			}

			errorInvalidToken();
			state = BOGUS_COMMENT;
		}
	};

	protected State TAG_NAME = new State() {
		public void parse() {
			int nameNdx = ndx;

			while (true) {
				ndx++;

				if (isEOF()) {
					errorEOF();
					state = DATA_STATE;
					return;
				}

				char c = input[ndx];

				if (equalsOne(c, TAG_WHITESPACES)) {
					state = BEFORE_ATTRIBUTE_NAME;
					tag.setName(substring(nameNdx, ndx));
					break;
				}

				if (c == '/') {
					state = SELF_CLOSING_START_TAG;
					tag.setName(substring(nameNdx, ndx));
					break;
				}

				if (c == '>') {
					state = DATA_STATE;
					tag.setName(substring(nameNdx, ndx));
					emitTag();
					break;
				}
			}
		}
	};

	protected State BEFORE_ATTRIBUTE_NAME = new State() {
		public void parse() {
			while (true) {
				ndx++;

				if (isEOF()) {
					errorEOF();
					state = DATA_STATE;
					return;
				}

				char c = input[ndx];

				if (equalsOne(c, TAG_WHITESPACES)) {
					continue;
				}

				if (c == '/') {
					state = SELF_CLOSING_START_TAG;
					return;
				}

				if (c == '>') {
					state = DATA_STATE;
					emitTag();
					return;
				}

				if (equalsOne(c, ATTR_INVALID_1)) {
					errorInvalidToken();
				}

				state = ATTRIBUTE_NAME;
				return;
			}
		}
	};

	protected State ATTRIBUTE_NAME = new State() {
		public void parse() {
			attrStartNdx = ndx;

			while (true) {
				ndx++;

				if (isEOF()) {
					errorEOF();
					state = DATA_STATE;
					return;
				}

				char c = input[ndx];

				if (equalsOne(c, TAG_WHITESPACES)) {
					attrEndNdx = ndx;
					state = AFTER_ATTRIBUTE_NAME;
					return;
				}

				if (c == '/') {
					attrEndNdx = ndx;
					_addAttribute();
					state = SELF_CLOSING_START_TAG;
					return;
				}

				if (c == '=') {
					attrEndNdx = ndx;
					state = BEFORE_ATTRIBUTE_VALUE;
					return;
				}

				if (c == '>') {
					state = DATA_STATE;
					attrEndNdx = ndx;
					_addAttribute();
					emitTag();
					return;
				}

				if (equalsOne(c, ATTR_INVALID_2)) {
					errorInvalidToken();
				}
			}
		}
	};

	protected State AFTER_ATTRIBUTE_NAME = new State() {
		public void parse() {
			while(true) {
				ndx++;

				if (isEOF()) {
					errorEOF();
					state = DATA_STATE;
					return;
				}

				char c = input[ndx];

				if (equalsOne(c, TAG_WHITESPACES)) {
					continue;
				}

				if (c == '/') {
					state = SELF_CLOSING_START_TAG;
					return;
				}
				if (c == '=') {
					state = BEFORE_ATTRIBUTE_VALUE;
					return;
				}
				if (c == '>') {
					state = DATA_STATE;
					emitTag();
					return;
				}
				if (equalsOne(c, ATTR_INVALID_2)) {
					errorInvalidToken();
				}

				_addAttribute();
				state = ATTRIBUTE_NAME;
				return;
			}
		}
	};

	protected State BEFORE_ATTRIBUTE_VALUE = new State() {
		public void parse() {
			while (true) {
				ndx++;

				if (isEOF()) {
					errorEOF();
					state = DATA_STATE;
					return;
				}

				char c = input[ndx];

				if (equalsOne(c, TAG_WHITESPACES)) {
					continue;
				}

				if (c == '\"') {
					state = ATTR_VALUE_DOUBLE_QUOTED;
					return;
				}
				if (c == '\'') {
					state = ATTR_VALUE_SINGLE_QUOTED;
					return;
				}
				if (c == '&') {
					state = ATTR_VALUE_UNQUOTED;
					ndx--;
					return;
				}
				if (c == '>') {
					errorInvalidToken();
					state = DATA_STATE;
					emitTag();
					return;
				}
				if (equalsOne(c, ATTR_INVALID_3)) {
					errorInvalidToken();
				}

				state = ATTR_VALUE_UNQUOTED;
				return;
			}
		}
	};

	protected State ATTR_VALUE_UNQUOTED = new State() {
		public void parse() {
			attrValueStartNdx = ndx;

			while (true) {
				ndx++;

				if (isEOF()) {
					errorEOF();
					state = DATA_STATE;
					return;
				}

				char c = input[ndx];

				if (equalsOne(c, TAG_WHITESPACES)) {
					_addAttributeWithValue();
					state = BEFORE_ATTRIBUTE_NAME;
					return;
				}

				if (c == '&') {
					// todo
				}

				if (c == '>') {
					_addAttributeWithValue();
					state = DATA_STATE;
					emitTag();
					return;
				}

				if (equalsOne(c, ATTR_INVALID_4)) {
					errorInvalidToken();
				}
			}
		}
	};

	protected State ATTR_VALUE_SINGLE_QUOTED = new State() {
		public void parse() {
			attrValueStartNdx = ndx + 1;

			while (true) {
				ndx++;

				if (isEOF()) {
					errorEOF();
					state = DATA_STATE;
					return;
				}

				char c = input[ndx];

				if (c == '\'') {
					_addAttributeWithValue();
					state = AFTER_ATTRIBUTE_VALUE_QUOTED;
					return;
				}
				if (c == '&') {
					// todo
				}
			}
		}
	};

	protected State ATTR_VALUE_DOUBLE_QUOTED = new State() {
		public void parse() {
			attrValueStartNdx = ndx + 1;

			while (true) {
				ndx++;

				if (isEOF()) {
					errorEOF();
					state = DATA_STATE;
					return;
				}

				char c = input[ndx];

				if (c == '"') {
					_addAttributeWithValue();
					state = AFTER_ATTRIBUTE_VALUE_QUOTED;
					return;
				}
				if (c == '&') {
					// todo
				}
			}
		}
	};


	protected State AFTER_ATTRIBUTE_VALUE_QUOTED = new State() {
		public void parse() {
			attrValueStartNdx = ndx;

			while (true) {
				ndx++;

				if (isEOF()) {
					errorEOF();
					state = DATA_STATE;
					return;
				}

				char c = input[ndx];

				if (equalsOne(c, TAG_WHITESPACES)) {
					state = BEFORE_ATTRIBUTE_NAME;
					return;
				}

				if (c == '/') {
					state = SELF_CLOSING_START_TAG;
					return;
				}

				if (c == '>') {
					state = DATA_STATE;
					emitTag();
					return;
				}

				errorInvalidToken();
				state = BEFORE_ATTRIBUTE_NAME;
			}
		}
	};

	protected State SELF_CLOSING_START_TAG = new State() {
		public void parse() {
			ndx++;

			if (isEOF()) {
				errorEOF();
				state = DATA_STATE;
				return;
			}

			char c = input[ndx];

			if (c == '>') {
				tag.setType(TagType.SELF_CLOSING);
				state = DATA_STATE;
				emitTag();
				return;
			}

			errorInvalidToken();

			state = BEFORE_ATTRIBUTE_NAME;
			ndx--;
		}
	};

	// ---------------------------------------------------------------- special

	protected State BOGUS_COMMENT = new State() {
		public void parse() {
			int tagEndNdx = find('>', total); 		// todo remove find

			if (tagEndNdx == -1) {
				tagEndNdx = total;
			}

			emitComment(ndx, tagEndNdx);

			state = DATA_STATE;
			ndx++;
		}
	};

	protected State MARKUP_DECLARATION_OPEN = new State() {
		public void parse() {
			ndx++;

			if (isEOF()) {
				errorEOF();
				state = BOGUS_COMMENT;
				return;
			}

			if (match(COMMENT_DASH, false)) {
				state = COMMENT_START;
				ndx++;
				return;
			}

			if (matchCaseInsensitiveWithUpper(_DOCTYPE, false)) {
				state = DOCTYPE;
				ndx += _DOCTYPE.length - 1;
				return;
			}

			// todo cdata see: 12.2.4.45

			errorInvalidToken();
			state = BOGUS_COMMENT;
		}
	};

	// ---------------------------------------------------------------- RAWTEXT

	protected int rawTextStart;		// todo prodji sve varijable i vidi da li se koriste!
	protected int rawTextEnd;
	protected char[] rawTagName;

	protected State RAWTEXT = new State() {
		public void parse() {
			while (true) {
				ndx++;

				if (isEOF()) {
					state = DATA_STATE;
					return;
				}

				char c = input[ndx];

				if (c == '<') {
					rawTextEnd = ndx;
					state = RAWTEXT_LESS_THAN_SIGN;
					return;
				}
			}
		}
	};

	protected State RAWTEXT_LESS_THAN_SIGN = new State() {
		public void parse() {
			ndx++;

			if (isEOF()) {
				state = RAWTEXT;
				return;
			}

			char c = input[ndx];

			if (c == '/') {
				state = RAWTEXT_END_TAG_OPEN;
				return;
			}

			state = RAWTEXT;
		}
	};

	protected State RAWTEXT_END_TAG_OPEN = new State() {
		public void parse() {
			ndx++;

			if (isEOF()) {
				state = RAWTEXT;
				return;
			}

			char c = input[ndx];

			if (isAlpha(c)) {
				state = RAWTEXT_END_TAG_NAME;
				return;
			}

			state = RAWTEXT;
		}
	};

	protected State RAWTEXT_END_TAG_NAME = new State() {
		public void parse() {
			int rawtextEndTagNameStartNdx = ndx;

			while (true) {
				ndx++;

				if (isEOF()) {
					state = RAWTEXT;
					return;
				}

				char c = input[ndx];

				if (equalsOne(c, TAG_WHITESPACES)) {
					if (isAppropriateTagName(rawTagName, rawtextEndTagNameStartNdx, ndx)) {
						emitText(rawTextStart, rawTextEnd);
						state = BEFORE_ATTRIBUTE_NAME;
						tag.start(rawTextEnd);
						tag.setName(substring(rawtextEndTagNameStartNdx, ndx));
						tag.setType(TagType.END);
					} else {
						state = RAWTEXT;
					}
					return;
				}

				if (c == '/') {
					if (isAppropriateTagName(rawTagName, rawtextEndTagNameStartNdx, ndx)) {
						emitText(rawTextStart, rawTextEnd);
						state = SELF_CLOSING_START_TAG;
						tag.start(rawTextEnd);
						tag.setName(substring(rawtextEndTagNameStartNdx, ndx));
						tag.setType(TagType.SELF_CLOSING);
					} else {
						state = RAWTEXT;
					}
					return;
				}

				if (c == '>') {
					if (isAppropriateTagName(rawTagName, rawtextEndTagNameStartNdx, ndx)) {
						emitText(rawTextStart, rawTextEnd);
						state = DATA_STATE;
						tag.start(rawTextEnd);
						tag.setName(substring(rawtextEndTagNameStartNdx, ndx));
						tag.setType(TagType.END);
						tag.end(ndx);
						emitTag();
					} else {
						state = RAWTEXT;
					}
					return;
				}
				if (isAlpha(c)) {
					continue;
				}

				state = RAWTEXT;
				return;
			}
		}
	};

	/**
	 * Similar to {@link #emitChar(char)}, emits mulitple chars at once.
	 */
	private void emitText(int from, int to) {
		while (from < to) {
			emitChar(input[from]);
			from++;
		}
	}

	// ---------------------------------------------------------------- RCDATA

	protected int rcdataStart;
	protected int rcdataEnd;
	protected char[] rcdataTagName;

	protected State RCDATA = new State() {
		public void parse() {
			while (true) {
				ndx++;

				if (isEOF()) {
					state = DATA_STATE;
					return;
				}

				char c = input[ndx];

				if (c == '<') {
					rcdataEnd = ndx;
					state = RCDATA_LESS_THAN_SIGN;
					return;
				}

				if (c == '&') {
					// todo
				}
			}
		}
	};

	protected State RCDATA_LESS_THAN_SIGN = new State() {
		public void parse() {
			ndx++;

			if (isEOF()) {
				state = RCDATA;
				return;
			}

			char c = input[ndx];

			if (c == '/') {
				state = RCDATA_END_TAG_OPEN;
				return;
			}

			state = RCDATA;
		}
	};

	protected State RCDATA_END_TAG_OPEN = new State() {
		public void parse() {
			ndx++;

			if (isEOF()) {
				state = RCDATA;
				return;
			}

			char c = input[ndx];

			if (isAlpha(c)) {
				state = RCDATA_END_TAG_NAME;
				return;
			}

			state = RCDATA;
		}
	};

	protected State RCDATA_END_TAG_NAME = new State() {
		public void parse() {
			int rcdataEndTagNameStartNdx = ndx;

			while (true) {
				ndx++;

				if (isEOF()) {
					state = RCDATA;
					return;
				}

				char c = input[ndx];

				if (equalsOne(c, TAG_WHITESPACES)) {
					if (isAppropriateTagName(rcdataTagName, rcdataEndTagNameStartNdx, ndx)) {
						emitText(rcdataStart, rcdataEnd);
						state = BEFORE_ATTRIBUTE_NAME;
						tag.start(rcdataEnd);
						tag.setName(substring(rcdataEndTagNameStartNdx, ndx));
						tag.setType(TagType.END);
					} else {
						state = RCDATA;
					}
					return;
				}

				if (c == '/') {
					if (isAppropriateTagName(rcdataTagName, rcdataEndTagNameStartNdx, ndx)) {
						emitText(rcdataStart, rcdataEnd);
						state = SELF_CLOSING_START_TAG;
						tag.start(rcdataEnd);
						tag.setName(substring(rcdataEndTagNameStartNdx, ndx));
						tag.setType(TagType.SELF_CLOSING);
					} else {
						state = RCDATA;
					}
					return;
				}

				if (c == '>') {
					if (isAppropriateTagName(rcdataTagName, rcdataEndTagNameStartNdx, ndx)) {
						emitText(rcdataStart, rcdataEnd);
						state = DATA_STATE;
						tag.start(rcdataEnd);
						tag.setName(substring(rcdataEndTagNameStartNdx, ndx));
						tag.setType(TagType.END);
						tag.end(ndx);
						emitTag();
					} else {
						state = RCDATA;
					}
					return;
				}

				if (isAlpha(c)) {
					continue;
				}

				state = RCDATA;
				return;
			}
		}
	};

	// ---------------------------------------------------------------- comments

	protected int commentStart;

	protected State COMMENT_START = new State() {
		public void parse() {
			ndx++;
			commentStart = ndx;

			if (isEOF()) {
				errorEOF();
				state = DATA_STATE;
				emitComment(commentStart, ndx);
				return;
			}

			char c = input[ndx];

			if (c == '-') {
				state = COMMENT_START_DASH;
				return;
			}

			if (c == '>') {
				errorInvalidToken();
				state = DATA_STATE;
				emitComment(commentStart, ndx);
				return;
			}

			state = COMMENT;
		}
	};

	protected State COMMENT_START_DASH = new State() {
		public void parse() {
			ndx++;

			if (isEOF()) {
				errorEOF();
				state = DATA_STATE;
				emitComment(commentStart, ndx);
				return;
			}

			char c = input[ndx];

			if (c == '-') {
				state = COMMENT_END;
				return;
			}
			if (c == '>') {
				errorInvalidToken();
				state = DATA_STATE;
				emitComment(commentStart, ndx);
			}

			state = COMMENT;
		}
	};

	protected State COMMENT = new State() {
		public void parse() {
			while (true) {
				ndx++;

				if (isEOF()) {
					errorEOF();
					state = DATA_STATE;
					emitComment(commentStart, ndx);
					return;
				}

				char c = input[ndx];

				if (c == '-') {
					state = COMMENT_END_DASH;
					return;
				}
			}
		}
	};

	protected State COMMENT_END_DASH = new State() {
		public void parse() {
			ndx++;

			if (isEOF()) {
				errorEOF();
				state = DATA_STATE;
				emitComment(commentStart, ndx);
				return;
			}

			char c = input[ndx];

			if (c == '-') {
				state = COMMENT_END;
				return;
			}

			state = COMMENT;
		}
	};

	protected State COMMENT_END = new State() {
		public void parse() {
			ndx++;

			if (isEOF()) {
				errorEOF();
				state = DATA_STATE;
				emitComment(commentStart, ndx);
				return;
			}

			char c = input[ndx];

			if (c == '>') {
				state = DATA_STATE;
				emitComment(commentStart, ndx - 2);
				return;
			}

			if (c == '!') {
				errorInvalidToken();
				state = COMMENT_END_BANG;
				return;
			}

			if (c == '-') {
				errorInvalidToken();
			} else {
				errorInvalidToken();
				state = COMMENT;
			}
		}
	};

	protected State COMMENT_END_BANG = new State() {
		public void parse() {
			ndx++;

			if (isEOF()) {
				errorEOF();
				state = DATA_STATE;
				emitComment(commentStart, ndx);
				return;
			}

			char c = input[ndx];

			if (c == '-') {
				state = COMMENT_END_DASH;
				return;
			}
			if (c == '>') {
				state = DATA_STATE;
				emitComment(commentStart, ndx - 3);
				return;
			}
			state = COMMENT;
		}
	};

	// ---------------------------------------------------------------- DOCTYPE

	protected State DOCTYPE = new State() {
		public void parse() {
			ndx++;

			if (isEOF()) {
				errorEOF();
				state = DATA_STATE;
				doctype.setQuirksMode(true);
				emitDoctype();
				return;
			}

			char c = input[ndx];

			if (equalsOne(c, TAG_WHITESPACES)) {
				state = BEFORE_DOCTYPE_NAME;
				return;
			}

			errorInvalidToken();
			state = BEFORE_DOCTYPE_NAME;
			ndx--;
		}
	};

	protected State BEFORE_DOCTYPE_NAME = new State() {
		public void parse() {
			while (true) {
				ndx++;

				if (isEOF()) {
					errorEOF();
					state = DATA_STATE;
					doctype.setQuirksMode(true);
					emitDoctype();
					return;
				}

				char c = input[ndx];

				if (equalsOne(c, TAG_WHITESPACES)) {
					continue;
				}

				if (c == '>') {
					errorInvalidToken();
					state = DATA_STATE;
					doctype.setQuirksMode(true);
					emitDoctype();
					return;
				}

				state = DOCTYPE_NAME;
				return;
			}
		}
	};

	protected State DOCTYPE_NAME = new State() {
		public void parse() {
			int nameStartNdx = ndx;

			while (true) {
				ndx++;

				if (isEOF()) {
					errorEOF();
					state = DATA_STATE;
					doctype.setName(nameStartNdx, ndx);
					doctype.setQuirksMode(true);
					emitDoctype();
					return;
				}

				char c = input[ndx];

				if (equalsOne(c, TAG_WHITESPACES)) {
					state = AFTER_DOCUMENT_NAME;
					doctype.setName(nameStartNdx, ndx);
					return;
				}

				if (c == '>') {
					state = DATA_STATE;
					doctype.setName(nameStartNdx, ndx);
					emitDoctype();
					return;
				}
			}
		}
	};

	protected State AFTER_DOCUMENT_NAME = new State() {
		public void parse() {
			while (true) {
				ndx++;

				if (isEOF()) {
					errorEOF();
					state = DATA_STATE;
					doctype.setQuirksMode(true);
					emitDoctype();
					return;
				}

				char c = input[ndx];

				if (equalsOne(c, TAG_WHITESPACES)) {
					continue;
				}

				if (c == '>') {
					state = DATA_STATE;
					emitDoctype();
					return;
				}

				if (matchCaseInsensitiveWithUpper(_PUBLIC, false)) {		// todo check all matches usage if ignore case or not
					ndx += _PUBLIC.length - 1;
					state = AFTER_DOCTYPE_PUBLIC_KEYWORD;
					return;
				}
				if (matchCaseInsensitiveWithUpper(_SYSTEM, false)) {
					ndx += _SYSTEM.length - 1;
					state = AFTER_DOCTYPE_SYSTEM_KEYWORD;
					return;
				}

				errorInvalidToken();
				state = BOGUS_DOCTYPE;
				doctype.setQuirksMode(true);
				return;
			}
		}
	};

	protected int doctypeIdNameStart;

	protected State AFTER_DOCTYPE_PUBLIC_KEYWORD = new State() {
		public void parse() {
			ndx++;

			if (isEOF()) {
				errorEOF();
				state = DATA_STATE;
				doctype.setQuirksMode(true);
				emitDoctype();
				return;
			}

			char c = input[ndx];

			if (equalsOne(c, TAG_WHITESPACES)) {
				state = BEFORE_DOCTYPE_PUBLIC_IDENTIFIER;
				return;
			}

			if (c == '\"') {
				errorInvalidToken();
				doctypeIdNameStart = ndx + 1;
				state = DOCTYPE_PUBLIC_IDENTIFIER_DOUBLE_QUOTED;
				return;
			}

			if (c == '\'') {
				errorInvalidToken();
				doctypeIdNameStart = ndx + 1;
				state = DOCTYPE_PUBLIC_IDENTIFIER_SINGLE_QUOTED;
				return;
			}

			if (c == '>') {
				errorInvalidToken();
				state = DATA_STATE;
				doctype.setQuirksMode(true);
				emitDoctype();
				return;
			}

			errorInvalidToken();
			state = BOGUS_DOCTYPE;
			doctype.setQuirksMode(true);
		}
	};

	protected State BEFORE_DOCTYPE_PUBLIC_IDENTIFIER = new State() {
		public void parse() {
			while(true) {
				ndx++;

				if (isEOF()) {
					errorEOF();
					state = DATA_STATE;
					emitDoctype();
					return;
				}

				char c = input[ndx];

				if (equalsOne(c, TAG_WHITESPACES)) {
					continue;
				}

				if (c == '\"') {
					doctypeIdNameStart = ndx + 1;
					state = DOCTYPE_PUBLIC_IDENTIFIER_DOUBLE_QUOTED;
					return;
				}

				if (c == '\'') {
					doctypeIdNameStart = ndx + 1;
					state = DOCTYPE_PUBLIC_IDENTIFIER_SINGLE_QUOTED;
					return;
				}

				if (c == '>') {
					errorInvalidToken();
					state = DATA_STATE;
					doctype.setQuirksMode(true);
					emitDoctype();
					return;
				}

				errorInvalidToken();
				doctype.setQuirksMode(true);
				state = BOGUS_DOCTYPE;
				return;
			}
		}
	};

	protected State DOCTYPE_PUBLIC_IDENTIFIER_DOUBLE_QUOTED = new State() {
		public void parse() {
			while (true) {
				ndx++;

				if (isEOF()) {
					doctype.setPublicIdentifier(doctypeIdNameStart, ndx);
					errorEOF();
					state = DATA_STATE;
					doctype.setQuirksMode(true);
					emitDoctype();
				}

				char c = input[ndx];

				if (c == '\"') {
					doctype.setPublicIdentifier(doctypeIdNameStart, ndx);
					state = AFTER_DOCTYPE_PUBLIC_IDENTIFIER;
					return;
				}

				if (c == '>') {
					doctype.setPublicIdentifier(doctypeIdNameStart, ndx);
					errorInvalidToken();
					state = DATA_STATE;
					doctype.setQuirksMode(true);
					emitDoctype();
					return;
				}
			}
		}
	};

	protected State DOCTYPE_PUBLIC_IDENTIFIER_SINGLE_QUOTED = new State() {
		public void parse() {
			while (true) {
				ndx++;

				if (isEOF()) {
					doctype.setPublicIdentifier(doctypeIdNameStart, ndx);
					errorEOF();
					state = DATA_STATE;
					doctype.setQuirksMode(true);
					emitDoctype();
				}

				char c = input[ndx];

				if (c == '\'') {
					doctype.setPublicIdentifier(doctypeIdNameStart, ndx);
					state = AFTER_DOCTYPE_PUBLIC_IDENTIFIER;
					return;
				}

				if (c == '>') {
					doctype.setPublicIdentifier(doctypeIdNameStart, ndx);
					errorInvalidToken();
					state = DATA_STATE;
					doctype.setQuirksMode(true);
					emitDoctype();
					return;
				}
			}
		}
	};

	protected State AFTER_DOCTYPE_PUBLIC_IDENTIFIER = new State() {
		public void parse() {
			ndx++;

			if (isEOF()) {
				errorEOF();
				state = DATA_STATE;
				doctype.setQuirksMode(true);
				emitDoctype();
				return;
			}

			char c = input[ndx];

			if (equalsOne(c, TAG_WHITESPACES)) {
				state = BETWEEN_DOCTYPE_PUBLIC_AND_SYSTEM_IDENTIFIERS;
				return;
			}

			if (c == '>') {
				state = DATA_STATE;
				emitDoctype();
				return;
			}

			if (c == '\"') {
				errorInvalidToken();
				doctypeIdNameStart = ndx + 1;
				state = DOCTYPE_SYSTEM_IDENTIFIER_DOUBLE_QUOTED;
				return;
			}

			if (c == '\'') {
				errorInvalidToken();
				doctypeIdNameStart = ndx + 1;
				state = DOCTYPE_SYSTEM_IDENTIFIER_SINGLE_QUOTED;
				return;
			}

			errorInvalidToken();
			doctype.setQuirksMode(true);
			state = BOGUS_DOCTYPE;
		}
	};

	protected State BETWEEN_DOCTYPE_PUBLIC_AND_SYSTEM_IDENTIFIERS = new State() {
		public void parse() {
			while (true) {
				ndx++;

				if (isEOF()) {
					errorEOF();
					state = DATA_STATE;
					emitDoctype();
					return;
				}

				char c = input[ndx];

				if (equalsOne(c, TAG_WHITESPACES)) {
					continue;
				}

				if (c == '>') {
					state = DATA_STATE;
					emitDoctype();
					return;
				}

				if (c == '\"') {
					doctypeIdNameStart = ndx + 1;
					state = DOCTYPE_SYSTEM_IDENTIFIER_DOUBLE_QUOTED;
					return;
				}

				if (c == '\'') {
					doctypeIdNameStart = ndx + 1;
					state = DOCTYPE_SYSTEM_IDENTIFIER_SINGLE_QUOTED;
					return;
				}

				errorInvalidToken();
				doctype.setQuirksMode(true);
				state = BOGUS_DOCTYPE;
				return;
			}
		}
	};


	protected State BOGUS_DOCTYPE = new State() {
		public void parse() {
			while (true) {
				ndx++;

				if (isEOF()) {
					state = DATA_STATE;
					emitDoctype();
					return;
				}

				char c = input[ndx];

				if (c == '>') {
					state = DATA_STATE;
					emitDoctype();
					return;
				}
			}
		}
	};

	protected State AFTER_DOCTYPE_SYSTEM_KEYWORD = new State() {
		public void parse() {
			ndx++;

			if (isEOF()) {
				errorEOF();
				state = DATA_STATE;
				doctype.setQuirksMode(true);
				emitDoctype();
				return;
			}

			char c = input[ndx];

			if (equalsOne(c, TAG_WHITESPACES)) {
				state = BEFORE_DOCTYPE_SYSTEM_IDENTIFIER;
				return;
			}

			if (c == '\"') {
				errorInvalidToken();
				doctypeIdNameStart = ndx + 1;
				state = DOCTYPE_SYSTEM_IDENTIFIER_DOUBLE_QUOTED;
				return;
			}

			if (c == '\'') {
				errorInvalidToken();
				doctypeIdNameStart = ndx + 1;
				state = DOCTYPE_SYSTEM_IDENTIFIER_SINGLE_QUOTED;
				return;
			}

			if (c == '>') {
				errorInvalidToken();
				state = DATA_STATE;
				doctype.setQuirksMode(true);
				emitDoctype();
				return;
			}

			errorInvalidToken();
			state = BOGUS_DOCTYPE;
			doctype.setQuirksMode(true);
		}
	};

	protected State BEFORE_DOCTYPE_SYSTEM_IDENTIFIER = new State() {
		public void parse() {
			while(true) {
				ndx++;

				if (isEOF()) {
					errorEOF();
					state = DATA_STATE;
					emitDoctype();
					return;
				}

				char c = input[ndx];

				if (equalsOne(c, TAG_WHITESPACES)) {
					continue;
				}

				if (c == '\"') {
					doctypeIdNameStart = ndx + 1;
					state = DOCTYPE_SYSTEM_IDENTIFIER_DOUBLE_QUOTED;
					return;
				}

				if (c == '\'') {
					doctypeIdNameStart = ndx + 1;
					state = DOCTYPE_SYSTEM_IDENTIFIER_SINGLE_QUOTED;
					return;
				}

				if (c == '>') {
					errorInvalidToken();
					state = DATA_STATE;
					doctype.setQuirksMode(true);
					emitDoctype();
					return;
				}

				errorInvalidToken();
				doctype.setQuirksMode(true);
				state = BOGUS_DOCTYPE;
				return;
			}
		}
	};

	protected State DOCTYPE_SYSTEM_IDENTIFIER_DOUBLE_QUOTED = new State() {
		public void parse() {
			while (true) {
				ndx++;

				if (isEOF()) {
					doctype.setSystemIdentifier(doctypeIdNameStart, ndx);
					errorEOF();
					state = DATA_STATE;
					doctype.setQuirksMode(true);
					emitDoctype();
				}

				char c = input[ndx];

				if (c == '\"') {
					doctype.setSystemIdentifier(doctypeIdNameStart, ndx);
					state = AFTER_DOCTYPE_SYSTEM_IDENTIFIER;
					return;
				}

				if (c == '>') {
					doctype.setSystemIdentifier(doctypeIdNameStart, ndx);
					errorInvalidToken();
					state = DATA_STATE;
					doctype.setQuirksMode(true);
					emitDoctype();
					return;
				}
			}
		}
	};

	protected State DOCTYPE_SYSTEM_IDENTIFIER_SINGLE_QUOTED = new State() {
		public void parse() {
			while (true) {
				ndx++;

				if (isEOF()) {
					doctype.setSystemIdentifier(doctypeIdNameStart, ndx);
					errorEOF();
					state = DATA_STATE;
					doctype.setQuirksMode(true);
					emitDoctype();
				}

				char c = input[ndx];

				if (c == '\'') {
					doctype.setSystemIdentifier(doctypeIdNameStart, ndx);
					state = AFTER_DOCTYPE_SYSTEM_IDENTIFIER;
					return;
				}

				if (c == '>') {
					doctype.setSystemIdentifier(doctypeIdNameStart, ndx);
					errorInvalidToken();
					state = DATA_STATE;
					doctype.setQuirksMode(true);
					emitDoctype();
					return;
				}
			}
		}
	};

	protected State AFTER_DOCTYPE_SYSTEM_IDENTIFIER = new State() {
		public void parse() {
			while(true) {
				ndx++;

				if (isEOF()) {
					errorEOF();
					state = DATA_STATE;
					doctype.setQuirksMode(true);
					emitDoctype();
					return;
				}

				char c = input[ndx];

				if (equalsOne(c, TAG_WHITESPACES)) {
					continue;
				}

				if (c == '>') {
					state = DATA_STATE;
					emitDoctype();
					return;
				}

				errorInvalidToken();
				state = BOGUS_DOCTYPE;
				// does NOT set the quirks mode!
			}
		}
	};


	// ---------------------------------------------------------------- SCRIPT

	protected int scriptStartNdx = -1;
	protected int scriptEndNdx = -1;

	protected State SCRIPT_DATA = new State() {
		public void parse() {

			while(true) {
				ndx++;

				if (isEOF()) {
					state = DATA_STATE;
					return;
				}

				char c = input[ndx];

				if (c == '<') {
					scriptEndNdx = ndx;
					state = SCRIPT_DATA_LESS_THAN_SIGN;
					return;
				}
			}
		}
	};

	protected State SCRIPT_DATA_LESS_THAN_SIGN = new State() {
		public void parse() {
			ndx++;

			if (isEOF()) {
				state = SCRIPT_DATA;
				ndx--;
				return;
			}

			char c = input[ndx];

			if (c == '/') {
				state = SCRIPT_DATA_END_TAG_OPEN;
				return;
			}
			if (c == '!') {
				if (scriptEscape == null) {
					// create script escape states only if really needed
					scriptEscape = new SriptEscape();
				}
				state = scriptEscape.SCRIPT_DATA_ESCAPE_START;
				return;
			}
			state = SCRIPT_DATA;
		}
	};

	protected int scriptEndTagName = -1;

	protected State SCRIPT_DATA_END_TAG_OPEN = new State() {
		public void parse() {
			ndx++;

			if (isEOF()) {
				state = SCRIPT_DATA;
				ndx--;
				return;
			}

			char c = input[ndx];

			if (isAlpha(c)) {
				state = SCRIPT_DATA_END_TAG_NAME;
				scriptEndTagName = ndx;
				return;
			}

			state = SCRIPT_DATA;
		}
	};

	protected State SCRIPT_DATA_END_TAG_NAME = new State() {
		public void parse() {
			while (true) {
				ndx++;

				if (isEOF()) {
					state = SCRIPT_DATA;
					return;
				}

				char c = input[ndx];

				if (equalsOne(c, TAG_WHITESPACES)) {
					if (isAppropriateTagName(_SCRIPT, scriptEndTagName, ndx)) {
						state = BEFORE_ATTRIBUTE_NAME;
					} else {
						state = SCRIPT_DATA;
					}
					return;
				}
				if (c == '/') {
					if (isAppropriateTagName(_SCRIPT, scriptEndTagName, ndx)) {
						state = SELF_CLOSING_START_TAG;
					} else {
						state = SCRIPT_DATA;
					}
					return;
				}
				if (c == '>') {
					if (isAppropriateTagName(_SCRIPT, scriptEndTagName, ndx)) {
						state = DATA_STATE;
						emitScript(scriptStartNdx, scriptEndNdx);
					} else {
						state = SCRIPT_DATA;
					}
					return;
				}
				if (isAlpha(c)) {
					continue;
				}
				state = SCRIPT_DATA;
				return;
			}
		}
	};

	// ---------------------------------------------------------------- SCRIPT ESCAPE

	protected SriptEscape scriptEscape = null;

	/**
	 * Since escaping states inside the SCRIPT tag are rare, we want to use them
	 * lazy, only when really needed. Therefore, they are all grouped inside separate
	 * class that will be instantiated only if needed.
	 */
	protected class SriptEscape {

		protected State SCRIPT_DATA_ESCAPE_START = new State() {
			public void parse() {
				ndx++;

				if (isEOF()) {
					state = SCRIPT_DATA;
					ndx--;
					return;
				}

				char c = input[ndx];

				if (c == '-') {
					state = SCRIPT_DATA_ESCAPE_START_DASH;
					return;
				}

				state = SCRIPT_DATA;
			}
		};

		protected State SCRIPT_DATA_ESCAPE_START_DASH = new State() {
			public void parse() {
				ndx++;

				if (isEOF()) {
					state = SCRIPT_DATA;
					return;
				}

				char c = input[ndx];

				if (c == '-') {
					state = SCRIPT_DATA_ESCAPED_DASH_DASH;
					return;
				}

				state = SCRIPT_DATA;
			}
		};

		protected State SCRIPT_DATA_ESCAPED_DASH_DASH = new State() {
			public void parse() {
				ndx++;

				if (isEOF()) {
					errorEOF();
					state = DATA_STATE;
					return;
				}

				char c = input[ndx];

				if (c == '-') {
					return;
				}

				if (c == '<') {
					state = SCRIPT_DATA_ESCAPED_LESS_THAN_SIGN;
					return;
				}

				if (c == '>') {
					state = SCRIPT_DATA;
					return;
				}

				state = SCRIPT_DATA_ESCAPED;
			}
		};

		protected int doubleEscapedNdx = -1;

		protected State SCRIPT_DATA_ESCAPED_LESS_THAN_SIGN = new State() {
			public void parse() {
				ndx++;

				if (isEOF()) {
					state = SCRIPT_DATA_ESCAPED;
					return;
				}

				char c = input[ndx];

				if (c == '/') {
					doubleEscapedNdx = -1;
					state = SCRIPT_DATA_ESCAPED_END_TAG_OPEN;
					return;
				}

				if (isAlpha(c)) {
					doubleEscapedNdx = ndx;
					state = SCRIPT_DATA_DOUBLE_ESCAPE_START;
					return;
				}

				state = SCRIPT_DATA_ESCAPED;
			}
		};

		protected State SCRIPT_DATA_ESCAPED = new State() {
			public void parse() {
				while (true) {
					ndx++;

					if (isEOF()) {
						errorEOF();
						emitScript(scriptStartNdx, ndx);
						state = DATA_STATE;        // todo 8.2.4.22 -> order is not consistent, should be error first.
						return;
					}

					char c = input[ndx];

					if (c == '-') {
						state = SCRIPT_DATA_ESCAPED_DASH;
						break;
					}

					if (c == '<') {
						state = SCRIPT_DATA_ESCAPED_LESS_THAN_SIGN;
						return;
					}
				}
			}
		};


		protected State SCRIPT_DATA_ESCAPED_DASH = new State() {
			public void parse() {
				ndx++;

				if (isEOF()) {
					errorEOF();
					state = DATA_STATE;
					return;
				}

				char c = input[ndx];

				if (c == '-') {
					state = SCRIPT_DATA_ESCAPED_DASH_DASH;
					return;
				}

				if (c == '<') {
					state = SCRIPT_DATA_ESCAPED_DASH_DASH;
					return;
				}

				state = SCRIPT_DATA_ESCAPED;
			}
		};

		protected State SCRIPT_DATA_ESCAPED_END_TAG_OPEN = new State() {
			public void parse() {
				ndx++;

				if (isEOF()) {
					state = SCRIPT_DATA_ESCAPED;
					return;
				}

				char c = input[ndx];

				if (isAlpha(c)) {
					//todo Create a new end tag token,
					state = SCRIPT_DATA_ESCAPED_END_TAG_NAME;
				}

				state = SCRIPT_DATA_ESCAPED;
			}
		};

		protected State SCRIPT_DATA_ESCAPED_END_TAG_NAME = new State() {
			public void parse() {
				while (true) {
					ndx++;

					if (isEOF()) {
						state = SCRIPT_DATA_ESCAPED;
						return;
					}

					char c = input[ndx];

					if (equalsOne(c, TAG_WHITESPACES)) {
						if (isAppropriateTagName(_SCRIPT, scriptEndTagName, ndx)) {
							state = BEFORE_ATTRIBUTE_NAME;
						} else {
							state = SCRIPT_DATA_ESCAPED;
						}
						return;
					}
					if (c == '/') {
						if (isAppropriateTagName(_SCRIPT, scriptEndTagName, ndx)) {
							state = SELF_CLOSING_START_TAG;
						} else {
							state = SCRIPT_DATA_ESCAPED;
						}
						return;
					}
					if (c == '>') {
						if (isAppropriateTagName(_SCRIPT, scriptEndTagName, ndx)) {
							state = DATA_STATE;
							emitTag();
						} else {
							state = SCRIPT_DATA_ESCAPED;
						}
						return;
					}
					if (isAlpha(c)) {
						continue;
					}
					state = SCRIPT_DATA_ESCAPED;
					return;
				}
			}
		};

		// ---------------------------------------------------------------- SCRIPT DOUBLE ESCAPE

		protected State SCRIPT_DATA_DOUBLE_ESCAPE_START = new State() {
			public void parse() {
				while (true) {
					ndx++;

					if (isEOF()) {
						state = SCRIPT_DATA_ESCAPED;
						return;
					}

					char c = input[ndx];

					if (equalsOne(c, TAG_WHITESPACES_OR_END)) {
						if (isAppropriateTagName(_SCRIPT, doubleEscapedNdx, ndx)) {
							state = SCRIPT_DATA_DOUBLE_ESCAPED;
						} else {
							state = SCRIPT_DATA_ESCAPED;
						}
						return;
					}

					if (isAlpha(c)) {
						continue;
					}
					state = SCRIPT_DATA_ESCAPED;
					return;
				}
			}
		};

		protected State SCRIPT_DATA_DOUBLE_ESCAPED = new State() {
			public void parse() {
				while (true) {
					ndx++;

					if (isEOF()) {
						errorEOF();
						state = DATA_STATE;
						return;
					}

					char c = input[ndx];

					if (c == '-') {
						state = SCRIPT_DATA_DOUBLE_ESCAPED_DASH;
						return;
					}

					if (c == '<') {
						state = SCRIPT_DATA_DOUBLE_ESCAPED_LESS_THAN_SIGN;
						return;
					}
				}
			}
		};

		protected State SCRIPT_DATA_DOUBLE_ESCAPED_DASH = new State() {
			public void parse() {
				ndx++;

				if (isEOF()) {
					errorEOF();
					state = DATA_STATE;
					return;
				}

				char c = input[ndx];

				if (c == '-') {
					state = SCRIPT_DATA_DOUBLE_ESCAPED_DASH_DASH;
					return;
				}
				if (c == '<') {
					state = SCRIPT_DATA_DOUBLE_ESCAPED_LESS_THAN_SIGN;
					return;
				}
				state = SCRIPT_DATA_DOUBLE_ESCAPED;
			}
		};

		protected State SCRIPT_DATA_DOUBLE_ESCAPED_DASH_DASH = new State() {
			public void parse() {
				while (true) {
					ndx++;

					if (isEOF()) {
						errorEOF();
						state = DATA_STATE;
						return;
					}

					char c = input[ndx];

					if (c == '-') {
						continue;
					}

					if (c == '<') {
						state = SCRIPT_DATA_DOUBLE_ESCAPED_LESS_THAN_SIGN;
						return;
					}
					if (c == '>') {
						state = SCRIPT_DATA;
						return;
					}
					state = SCRIPT_DATA_DOUBLE_ESCAPED;
					return;
				}
			}
		};

		protected int doubleEscapedEndTag = -1;

		protected State SCRIPT_DATA_DOUBLE_ESCAPED_LESS_THAN_SIGN = new State() {
			public void parse() {
				ndx++;

				if (isEOF()) {
					state = SCRIPT_DATA_DOUBLE_ESCAPED;
					return;
				}

				char c = input[ndx];

				if (c == '/') {
					state = SCRIPT_DATA_DOUBLE_ESCAPE_END;
					return;
				}

				state = SCRIPT_DATA_DOUBLE_ESCAPED;
			}
		};

		protected State SCRIPT_DATA_DOUBLE_ESCAPE_END = new State() {
			public void parse() {
				doubleEscapedEndTag = ndx + 1;

				while (true) {
					ndx++;

					if (isEOF()) {
						state = SCRIPT_DATA_DOUBLE_ESCAPED;
						return;
					}

					char c = input[ndx];

					if (equalsOne(c, TAG_WHITESPACES_OR_END)) {
						if (isAppropriateTagName(_SCRIPT, doubleEscapedEndTag, ndx)) {
							state = SCRIPT_DATA_ESCAPED;
						} else {
							state = SCRIPT_DATA_DOUBLE_ESCAPED;
						}
						return;
					}
					if (isAlpha(c)) {
						continue;
					}

					state = SCRIPT_DATA_DOUBLE_ESCAPED;
					return;
				}
			}
		};
	}

	// ---------------------------------------------------------------- emit

	protected int textStartNdx = -1;
	protected int textEndNdx = -1;
	protected int attrStartNdx = -1;
	protected int attrEndNdx = -1;
	protected int attrValueStartNdx = -1;

	private void _addAttribute() {
		_addAttribute(substring(attrStartNdx, attrEndNdx), null);
	}

	private void _addAttributeWithValue() {
		_addAttribute(substring(attrStartNdx, attrEndNdx), substring(attrValueStartNdx, ndx));
	}

	private void _addAttribute(String attrName, String attrValue) {
		if (tag.hasAttribute(attrName, false)) {
			_error("Ignored duplicated attribute: " + attrName);
		} else {
			tag.addAttribute(attrName, attrValue);
		}
		attrStartNdx = -1;
		attrEndNdx = -1;
		attrValueStartNdx = -1;
	}

	protected void emitTag() {
		flushText();

		tag.end(ndx + 1);

		if (tag.getType().isStartingTag()) {

			if (tag.matchTagName(_SCRIPT)) {
				scriptStartNdx = ndx + 1;
				state = SCRIPT_DATA;
				return;
			}

			// detect RAWTEXT tags

			for (char[] rawtextTagName : RAWTEXT_TAGS) {
				if (tag.matchTagName(rawtextTagName)) {
					state = RAWTEXT;
					rawTextStart = ndx + 1;
					rawTagName = rawtextTagName;
					break;
				}
			}

			// detect RCDATA tag

			for (char[] rcdataTextTagName : RCDATA_TAGS) {
				if (tag.matchTagName(rcdataTextTagName)) {
					state = RCDATA;
					rcdataStart = ndx + 1;
					rcdataTagName = rcdataTextTagName;
					break;
				}
			}

			tag.increaseDeepLevel();
		}

		visitor.tag(tag);

		if (tag.getType().isEndingTag()) {
			tag.decreaseDeepLevel();
		}
	}

	protected void emitComment(int from, int to) {
		flushText();

		CharSequence comment = charSequence(from, to);
		visitor.comment(comment);

		commentStart = -1;
	}

	/**
	 * Flushes text buffer. Does nothing if buffer does not exist
	 * or it is empty. Must be called before every non-text visit method!
	 */
	protected void flushText() {
		if (textLen != 0) {
			char[] textToEmit = new char[textLen];
			System.arraycopy(text, 0, textToEmit, 0, textLen);
			visitor.text(CharBuffer.wrap(textToEmit));	// todo wrap or toString()
		}
		textLen = 0;
	}

	protected void emitScript(int from, int to) {
		flushText();

		tag.increaseDeepLevel();

		visitor.script(tag, substring(from, to));		// todo da li za script() treba specijalna visit metoda kao sto sada ima?

		tag.decreaseDeepLevel();
		scriptStartNdx = -1;
		scriptEndNdx = -1;
	}

	protected void emitDoctype() {
		flushText();

		visitor.doctype(doctype);

		doctype.reset();
	}

	// ---------------------------------------------------------------- error

	protected void errorEOF() {
		_error("Parse error: EOF");
	}

	protected void errorInvalidToken() {
		_error("Parse error: invalid token");
	}

	/**
	 * Prepares error message and reports it to the visitor.
	 * todo in the error message, add text that surrounds the error position
	 */
	protected void _error(String message) {
		flushText();

		int position = ndx;

		if (calculatePosition) {
			Position currentPosition = position();
			message = message
					.concat(StringPool.SPACE)
					.concat(currentPosition.toString());
		} else {
			message = message
					.concat(" [@")
					.concat(Integer.toString(position))
					.concat(StringPool.RIGHT_SQ_BRACKET);
		}

		visitor.error(message);
	}

	// ---------------------------------------------------------------- util

	private boolean isAppropriateTagName(char[] lowerCaseNameToMatch, int from, int to) {
		int len = to - from;

		if (len != lowerCaseNameToMatch.length) {
			return false;
		}

		for (int i = from, k = 0; i < to; i++, k++) {
			char c = input[i];

			c = CharUtil.toLowerAscii(c);

			if (c != lowerCaseNameToMatch[k]) {
				return false;
			}
		}
		return true;
	}

	// ---------------------------------------------------------------- const data

	protected State state = DATA_STATE;

	public static final char[] TAG_WHITESPACES = new char[] {'\t', '\n', '\r', ' '};
	private static final char[] TAG_WHITESPACES_OR_END = new char[] {'\t', '\n', '\r', ' ', '/', '>'};
	private static final char[] ATTR_INVALID_1 = new char[] {'\"', '\'', '<', '='};
	private static final char[] ATTR_INVALID_2 = new char[] {'\"', '\'', '<'};
	private static final char[] ATTR_INVALID_3 = new char[] {'<', '=', '`'};
	private static final char[] ATTR_INVALID_4 = new char[] {'"', '\'', '<', '=', '`'};
	private static final char[] COMMENT_DASH = new char[] {'-', '-'};
	private static final char[] _DOCTYPE = new char[] {'D', 'O', 'C', 'T', 'Y', 'P', 'E'};
	private static final char[] _SCRIPT = new char[] {'s', 'c', 'r', 'i', 'p', 't'};
	private static final char[] _XMP = new char[] {'x', 'm', 'p'};
	private static final char[] _STYLE = new char[] {'s', 't', 'y', 'l', 'e'};
	private static final char[] _IFRAME = new char[] {'i', 'f', 'r', 'a', 'm', 'e'};
	private static final char[] _NOFRAMES = new char[] {'n', 'o', 'f', 'r', 'a', 'm', 'e', 's'};
	private static final char[] _NOEMBED = new char[] {'n', 'o', 'e', 'm', 'b', 'e', 'd'};
	private static final char[] _NOSCRIPT = new char[] {'n', 'o', 's', 'c', 'r', 'i', 'p', 't'};
	private static final char[] _TEXTAREA = new char[] {'t', 'e', 'x', 't', 'a', 'r', 'e', 'a'};
	private static final char[] _TITLE = new char[] {'t', 'i', 't', 'l', 'e'};
	private static final char[] _PUBLIC = new char[] {'P', 'U', 'B', 'L', 'I', 'C'};
	private static final char[] _SYSTEM = new char[] {'S', 'Y', 'S', 'T', 'E', 'M'};

	// 'script' is handled by its states todo check this!
	private static final char[][] RAWTEXT_TAGS = new char[][] {		// CDATA
			_XMP, _STYLE, _IFRAME, _NOEMBED, _NOFRAMES, _NOSCRIPT,
	};

	private static final char[][] RCDATA_TAGS = new char[][] {
			_TEXTAREA, _TITLE
	};

}