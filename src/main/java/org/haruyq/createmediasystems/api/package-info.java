/**
 * What other mods may build on. Everything under this package keeps working from one release to the next of a major
 * version: it may gain types and members, never lose or change one. A type marked ApiStatus.Experimental may change
 * in a minor release (1.x.0) instead. Everything outside this package is CMS's own and changes without notice, as do
 * the private methods mixins might target.
 *
 * <p>Declare the range of CMS versions a mod works with in its neoforge.mods.toml: from the one it was built against
 * to the next major one ({@code [1.4.6,2)}), or the next minor one if it uses experimental types ({@code [1.4.6,1.5)}).
 * Out of that range NeoForge stops the game at loading, saying which mod to update, instead of a NoSuchMethodError.
 *
 * <p>Types a mod implements ({@code OverrideOnly}) only ever gain default methods; types CMS implements
 * ({@code NonExtendable}) may gain any, so are not for other mods to implement. ApiCompatibilityTest holds CMS to
 * this against src/test/resources/api/api-surface.txt.
 */
package org.haruyq.createmediasystems.api;
