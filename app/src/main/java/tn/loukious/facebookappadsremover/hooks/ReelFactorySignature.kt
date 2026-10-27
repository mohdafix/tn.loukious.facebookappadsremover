package tn.loukious.facebookappadsremover.hooks

/**
 * Stable *shape* of Facebook's reel button factory, not its obfuscated name.
 *
 * 578 had 19 parameters; 580 added a trailing boolean; subsequent versions add
 * further trailing flags or metadata. Keep the established argument slots for
 * the download action, and preserve any additional arguments from a real native
 * button created for that exact reel. If Facebook inserts or rearranges
 * arguments in that prefix, fail closed until re-reversed.
 */
internal object ReelFactorySignature {
    private const val SESSION = "com.facebook.auth.usersession.FbUserSession"
    private const val FUNCTION1 = "kotlin.jvm.functions.Function1"

    fun accepts(types: List<String>): Boolean =
        types.size >= 19 &&
            types[0] == SESSION &&
            isBoolean(types[6]) && isBoolean(types[7]) &&
            // Facebook declares the test-id slot as Object (String values
            // are accepted); the following caption/metadata slots are String or CharSequence.
            (types[8] == "java.lang.Object" || types[8] == "java.lang.String" || types[8] == "java.lang.CharSequence") &&
            (types[9] == "java.lang.String" || types[9] == "java.lang.CharSequence" || types[9] == "java.lang.Object") &&
            (types[10] == "java.lang.String" || types[10] == "java.lang.CharSequence" || types[10] == "java.lang.Object") &&
            (11..14).all { types[it] == FUNCTION1 } &&
            (types[15] == "int" || types[15] == "java.lang.Integer" || types[15] == FUNCTION1) &&
            (16..18).all { isBoolean(types[it]) }

    private fun isBoolean(type: String): Boolean =
        type == "boolean" || type == "java.lang.Boolean"
}
