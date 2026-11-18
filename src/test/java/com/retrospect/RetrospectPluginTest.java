package com.retrospect;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Starts RuneLite with Retrospect loaded. Run with: gradlew run */
public class RetrospectPluginTest
{
	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(RetrospectPlugin.class);
		RuneLite.main(args);
	}
}
