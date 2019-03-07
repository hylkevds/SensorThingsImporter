/*
 * Copyright (C) 2019 Fraunhofer IOSB
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

import de.fraunhofer.iosb.ilt.sensorthingsimporter.ImportException;
import java.io.IOException;
import java.nio.charset.Charset;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author scf
 */
public class UrlUtils {

	/**
	 * The logger for this class.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(UrlUtils.class);

	private UrlUtils() {
		// Utility class.
	}

	public static String fetchFromUrl(String targetUrl) throws ImportException {
		try {
			LOGGER.info("Fetching: {}", targetUrl);
			CloseableHttpClient client = HttpClients.createSystem();
			HttpGet get = new HttpGet(targetUrl);
			CloseableHttpResponse response = client.execute(get);
			String data = EntityUtils.toString(response.getEntity(), Charset.forName("UTF-8"));
			return data;
		} catch (IOException ex) {
			LOGGER.error("Failed to fetch url " + targetUrl, ex);
			throw new ImportException("Failed to fetch url " + targetUrl, ex);
		}
	}

}