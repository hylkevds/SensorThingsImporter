/*
 * Copyright (C) 2018 Fraunhofer IOSB
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.fraunhofer.iosb.ilt.sensorthingsimporter.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author scf
 */
public class Translator {

	/**
	 * The logger for this class.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(Translator.class);
	private Map<String, String> replaces;

	public Translator() {
		replaces = new HashMap<>();
	}

	public Translator(String json) throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		replaces = mapper.readValue(json, new TypeReference<Map<String, String>>() {
		});
	}

	public String translate(String input) {
		String result = replaces.get(input);
		if (result == null) {
			return input;
		}
		return result;
	}

	public void put(String input, String output) {
		replaces.put(input, output);
	}

	public String replaceIn(String source, boolean urlEncode) {
		Pattern pattern = Pattern.compile(Pattern.quote("{") + "([^}]+)}");
		Matcher matcher = pattern.matcher(source);
		StringBuilder result = new StringBuilder();
		int lastEnd = 0;
		while (matcher.find()) {
			int end = matcher.end();
			int start = matcher.start();
			result.append(source.substring(lastEnd, start));
			String key = matcher.group(1);
			String value = replaces.get(key);
			if (value == null) {
				LOGGER.error("No replacement for {}", key);
			}
			if (urlEncode) {
				try {
					result.append(URLEncoder.encode(value, "UTF-8"));
				} catch (UnsupportedEncodingException ex) {
					LOGGER.error("UTF-8 not supported??", ex);
				}
			} else {
				result.append(value);
			}
			lastEnd = end;
		}
		if (lastEnd < source.length()) {
			result.append(source.substring(lastEnd, source.length()));
		}
		return result.toString();
	}

}