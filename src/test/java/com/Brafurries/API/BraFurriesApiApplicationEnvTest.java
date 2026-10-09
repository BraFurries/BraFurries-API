package com.Brafurries.API;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BraFurriesApiApplicationEnvTest {

	@Test
	void stripWrappingQuotesRemovesMatchingSingleOrDoubleQuotes() {
		assertEquals("secret$1", BraFurriesApiApplication.stripWrappingQuotes("'secret$1'"));
		assertEquals("secret$1", BraFurriesApiApplication.stripWrappingQuotes("\"secret$1\""));
	}

	@Test
	void stripWrappingQuotesKeepsUnquotedAndMismatchedValues() {
		assertEquals("secret$1", BraFurriesApiApplication.stripWrappingQuotes("secret$1"));
		assertEquals("'secret$1", BraFurriesApiApplication.stripWrappingQuotes("'secret$1"));
		assertNull(BraFurriesApiApplication.stripWrappingQuotes(null));
	}
}
