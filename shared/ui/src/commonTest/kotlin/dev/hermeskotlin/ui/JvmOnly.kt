package dev.hermeskotlin.ui

/**
 * A test that reads the string catalogue (Compose's `getString`) through the code it checks. Android host tests
 * have no Android resources to read it from, so there the test is skipped; on the JVM it runs.
 */
expect annotation class JvmOnly()
