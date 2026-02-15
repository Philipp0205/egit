/*******************************************************************************
 * Copyright (C) 2026, Eclipse EGit contributors
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.core.internal.pullrequest;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.egit.core.internal.bitbucket.BitbucketClient;
import org.eclipse.egit.core.internal.github.GitHubClient;

/**
 * Factory for creating pull request client instances based on configured
 * provider type
 */
public class PullRequestClientFactory {

	private static final String PREF_PROVIDER_TYPE = "pullrequest_provider_type"; //$NON-NLS-1$

	private static final String PREF_BITBUCKET_SERVER_URL = "bitbucket_server_url"; //$NON-NLS-1$

	private static final String PREF_BITBUCKET_PROJECT_KEY = "bitbucket_project_key"; //$NON-NLS-1$

	private static final String PREF_BITBUCKET_REPO_SLUG = "bitbucket_repo_slug"; //$NON-NLS-1$

	private static final String PREF_BITBUCKET_ACCESS_TOKEN = "bitbucket_access_token"; //$NON-NLS-1$

	private static final String PREF_GITHUB_OWNER = "github_owner"; //$NON-NLS-1$

	private static final String PREF_GITHUB_REPO = "github_repo"; //$NON-NLS-1$

	private static final String PREF_GITHUB_ACCESS_TOKEN = "github_access_token"; //$NON-NLS-1$

	/**
	 * Configuration holder for pull request clients
	 */
	public static class ClientConfig {
		/**
		 * Provider type
		 */
		public PullRequestProviderType providerType;

		/**
		 * Bitbucket server URL
		 */
		public String bitbucketServerUrl;

		/**
		 * Bitbucket project key
		 */
		public String bitbucketProjectKey;

		/**
		 * Bitbucket repository slug
		 */
		public String bitbucketRepoSlug;

		/**
		 * Bitbucket access token
		 */
		public String bitbucketAccessToken;

		/**
		 * GitHub owner (user or organization)
		 */
		public String githubOwner;

		/**
		 * GitHub repository name
		 */
		public String githubRepo;

		/**
		 * GitHub access token
		 */
		public String githubAccessToken;
	}

	/**
	 * Creates a pull request client based on current preferences
	 *
	 * @return the configured client, or null if not properly configured
	 */
	public static IPullRequestClient createClient() {
		ClientConfig config = loadConfig();
		if (config == null || config.providerType == null) {
			System.err.println("PullRequestClientFactory: config is null or providerType is null"); //$NON-NLS-1$
			return null;
		}

		IPullRequestClient client = createClient(config);
		if (client == null) {
			System.err.println("PullRequestClientFactory: createClient returned null for provider: " + config.providerType); //$NON-NLS-1$
			System.err.println("  GitHub config - owner: '" + config.githubOwner + "', repo: '" + config.githubRepo + "', token: " + (config.githubAccessToken != null && !config.githubAccessToken.isEmpty() ? "<set>" : "<empty>")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		}
		return client;
	}

	/**
	 * Creates a pull request client for the specified configuration
	 *
	 * @param config
	 *            the client configuration
	 * @return the configured client, or null if configuration is invalid
	 */
	public static IPullRequestClient createClient(ClientConfig config) {
		if (config == null || config.providerType == null) {
			return null;
		}

		switch (config.providerType) {
		case BITBUCKET:
			if (isBlank(config.bitbucketServerUrl)
					|| isBlank(config.bitbucketAccessToken)
					|| isBlank(config.bitbucketProjectKey)
					|| isBlank(config.bitbucketRepoSlug)) {
				return null;
			}
			return new BitbucketClient(config.bitbucketServerUrl,
					config.bitbucketProjectKey, config.bitbucketRepoSlug,
					config.bitbucketAccessToken);

		case GITHUB:
			if (isBlank(config.githubOwner) || isBlank(config.githubRepo)
					|| isBlank(config.githubAccessToken)) {
				return null;
			}
			return new GitHubClient(config.githubOwner, config.githubRepo,
					config.githubAccessToken);

		default:
			return null;
		}
	}

	/**
	 * Loads the client configuration from preferences
	 *
	 * @return the configuration, or null if not configured
	 */
	public static ClientConfig loadConfig() {
		// Read from UI plugin preferences (org.eclipse.egit.ui)
		IEclipsePreferences prefs = InstanceScope.INSTANCE
				.getNode("org.eclipse.egit.ui"); //$NON-NLS-1$

		ClientConfig config = new ClientConfig();

		String providerTypeStr = prefs.get(PREF_PROVIDER_TYPE, "BITBUCKET"); //$NON-NLS-1$
		try {
			config.providerType = PullRequestProviderType
					.valueOf(providerTypeStr);
		} catch (IllegalArgumentException e) {
			config.providerType = PullRequestProviderType.BITBUCKET;
		}

		config.bitbucketServerUrl = prefs.get(PREF_BITBUCKET_SERVER_URL, ""); //$NON-NLS-1$
		config.bitbucketProjectKey = prefs.get(PREF_BITBUCKET_PROJECT_KEY,
				""); //$NON-NLS-1$
		config.bitbucketRepoSlug = prefs.get(PREF_BITBUCKET_REPO_SLUG, ""); //$NON-NLS-1$
		config.bitbucketAccessToken = prefs.get(PREF_BITBUCKET_ACCESS_TOKEN,
				""); //$NON-NLS-1$

		config.githubOwner = prefs.get(PREF_GITHUB_OWNER, ""); //$NON-NLS-1$
		config.githubRepo = prefs.get(PREF_GITHUB_REPO, ""); //$NON-NLS-1$
		config.githubAccessToken = prefs.get(PREF_GITHUB_ACCESS_TOKEN, ""); //$NON-NLS-1$

		return config;
	}

	private static boolean isBlank(String str) {
		return str == null || str.trim().isEmpty();
	}
}
