package com.androscan.app.util

/**
 * Validates bovine eartags for HU, CZ, SK, SI, PL, RO, DE, AT.
 *
 * Check-digit rules follow the national LOM definitions documented by
 * Bayerisches HI-Tier (where a formula is known). Countries without a
 * published check digit are validated by country code + length only.
 */
object EartagCheckDigit {

    /** Shortest accepted animal number (SI legacy). */
    private const val MIN_NUMBER_LENGTH = 6

    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data object InvalidLength : ValidationResult()
        data object InvalidCheckDigit : ValidationResult()
        data object Unsupported : ValidationResult()

        val errorMessage: String?
            get() = when (this) {
                Valid -> null
                InvalidLength -> "Ungültige Länge der Ohrmarke"
                InvalidCheckDigit -> "Prüfziffer ungültig"
                Unsupported -> "Ohrmarke nicht erkannt"
            }
    }

    private val ALPHA_TO_ISO = mapOf(
        "AT" to "040",
        "DE" to "276",
        "HU" to "348",
        "CZ" to "203",
        "SK" to "703",
        "SI" to "705",
        "PL" to "616",
        "RO" to "642"
    )

    private val ISO_TO_ALPHA = ALPHA_TO_ISO.entries.associate { (k, v) -> v to k }

    fun isValid(payload: String): Boolean = validate(payload) is ValidationResult.Valid

    fun validate(payload: String): ValidationResult {
        val cleaned = payload.trim().uppercase().replace(" ", "").replace("-", "")
        if (cleaned.length < MIN_NUMBER_LENGTH) return ValidationResult.InvalidLength

        val parsed = parse(cleaned)
        if (parsed != null) {
            if (parsed.number.length < MIN_NUMBER_LENGTH) return ValidationResult.InvalidLength
            return validateCountry(parsed.country, parsed.number)
        }

        if (cleaned.all { it.isDigit() }) {
            return validateBareNumeric(cleaned)
        }
        return ValidationResult.Unsupported
    }

    private fun validateCountry(country: String, number: String): ValidationResult {
        return when (country) {
            "AT" -> validateAt(number)
            "DE" -> validateDe(number)
            "HU" -> validateHu(number)
            "CZ" -> validateCz(number)
            "SK" -> validateSk(number)
            "SI" -> validateSi(number)
            "PL" -> validatePl(number)
            "RO" -> validateRo(number)
            else -> ValidationResult.Unsupported
        }
    }

    private fun validateBareNumeric(number: String): ValidationResult {
        if (number.length < MIN_NUMBER_LENGTH) return ValidationResult.InvalidLength
        return when (number.length) {
            6, 7 -> validateSi(number)
            8 -> firstOk(validateSi(number), validateSk(number))
            9 -> validateAt(number)
            10 -> firstOk(validateDe(number), validateHu(number))
            12 -> firstOk(validatePl(number), validateRo(number))
            else -> ValidationResult.InvalidLength
        }
    }

    /** Prefer Valid; else prefer check-digit error over length; else first failure. */
    private fun firstOk(a: ValidationResult, b: ValidationResult): ValidationResult {
        if (a is ValidationResult.Valid) return a
        if (b is ValidationResult.Valid) return b
        if (a is ValidationResult.InvalidCheckDigit || b is ValidationResult.InvalidCheckDigit) {
            return ValidationResult.InvalidCheckDigit
        }
        return a
    }

    internal data class Parsed(val country: String, val number: String)

    internal fun parse(raw: String): Parsed? {
        val value = raw.trim().uppercase().replace(" ", "").replace("-", "")
        if (value.length < 4) return null

        val alpha = value.take(2)
        if (alpha in ALPHA_TO_ISO && value.drop(2).all { it.isDigit() }) {
            return Parsed(alpha, normalizeRepeatedSuffix(value.drop(2)))
        }

        val iso = value.take(3)
        if (iso in ISO_TO_ALPHA && value.drop(3).all { it.isDigit() }) {
            val digits = value.drop(3).trimStart('0').ifEmpty { "0" }
            val number = when (ISO_TO_ALPHA.getValue(iso)) {
                "AT" -> value.drop(3).takeLast(9)
                "DE" -> value.drop(3).takeLast(10)
                "HU" -> value.drop(3).takeLast(10)
                "CZ" -> value.drop(3).takeLast(9)
                "SK" -> {
                    val body = value.drop(3).trimStart('0')
                    if (body.length <= 8) body.padStart(8, '0') else body.takeLast(9)
                }
                "SI" -> value.drop(3).trimStart('0').ifEmpty { "0" }
                "PL" -> value.drop(3).takeLast(12)
                "RO" -> value.drop(3).takeLast(12)
                else -> digits
            }
            return Parsed(ISO_TO_ALPHA.getValue(iso), normalizeRepeatedSuffix(number))
        }

        return null
    }

    private fun normalizeRepeatedSuffix(number: String): String {
        if (number.length == 13 && number.substring(2, 6) == number.takeLast(4)) {
            return number.dropLast(4)
        }
        if (number.length == 14 && number.substring(5, 9) == number.takeLast(4)) {
            return number.dropLast(4)
        }
        return number
    }

    private fun validateAt(number: String): ValidationResult {
        if (!number.all { it.isDigit() }) return ValidationResult.Unsupported
        if (number.length < 9) return ValidationResult.InvalidLength
        if (number.length != 9) return ValidationResult.InvalidLength
        val d = number.map { it.digitToInt() }
        val sum = (d[0] + d[2] + d[4] + d[7]) * 2 + (d[1] + d[3] + d[5] + d[8])
        var expected = sum % 9
        if (expected == 0) expected = 9
        return if (d[6] == expected) ValidationResult.Valid else ValidationResult.InvalidCheckDigit
    }

    private fun validateDe(number: String): ValidationResult {
        if (!number.all { it.isDigit() }) return ValidationResult.Unsupported
        if (number.length < 10) return ValidationResult.InvalidLength
        return if (number.length == 10) ValidationResult.Valid else ValidationResult.InvalidLength
    }

    private fun validateHu(number: String): ValidationResult {
        if (!number.all { it.isDigit() }) return ValidationResult.Unsupported
        if (number.length < 10) return ValidationResult.InvalidLength
        return if (number.length == 10) ValidationResult.Valid else ValidationResult.InvalidLength
    }

    private fun validateCz(number: String): ValidationResult {
        if (!number.all { it.isDigit() }) return ValidationResult.Unsupported
        if (number.length < 9) return ValidationResult.InvalidLength
        return if (number.length == 9) ValidationResult.Valid else ValidationResult.InvalidLength
    }

    private fun validateSk(number: String): ValidationResult {
        if (!number.all { it.isDigit() }) return ValidationResult.Unsupported
        if (number.length < 8) return ValidationResult.InvalidLength
        return if (number.length == 8 || number.length == 9) {
            ValidationResult.Valid
        } else {
            ValidationResult.InvalidLength
        }
    }

    private fun validateSi(number: String): ValidationResult {
        if (!number.all { it.isDigit() }) return ValidationResult.Unsupported
        if (number.length < MIN_NUMBER_LENGTH) return ValidationResult.InvalidLength
        return when (number.length) {
            6, 7 -> ValidationResult.Valid
            8 -> {
                val d = number.map { it.digitToInt() }
                val sum = 3 * d[1] + 7 * d[2] + 9 * d[3] + 11 * d[4] +
                    13 * d[5] + 17 * d[6] + 19 * d[7]
                if (d[0] == sum % 10) ValidationResult.Valid else ValidationResult.InvalidCheckDigit
            }
            else -> ValidationResult.InvalidLength
        }
    }

    private fun validatePl(number: String): ValidationResult {
        if (!number.all { it.isDigit() }) return ValidationResult.Unsupported
        if (number.length < 12) return ValidationResult.InvalidLength
        if (number.length != 12) return ValidationResult.InvalidLength
        if (!number.startsWith("00")) return ValidationResult.Unsupported
        val expectedFormula1 = plCheckDigitFormula1(number.take(11))
        if (number.last().digitToInt() == expectedFormula1) return ValidationResult.Valid
        if (isPlFormula2Series(number.take(11))) {
            return if (number.last().digitToInt() == plCheckDigitFormula2(number.take(11))) {
                ValidationResult.Valid
            } else {
                ValidationResult.InvalidCheckDigit
            }
        }
        return ValidationResult.InvalidCheckDigit
    }

    private fun plCheckDigitFormula1(body11: String): Int {
        var sum = 0
        for (i in body11.indices) {
            val digit = body11[i].digitToInt()
            sum += if ((i + 1) % 2 == 1) digit * 3 else digit
        }
        return (10 - (sum % 10)) % 10
    }

    private fun plCheckDigitFormula2(body11: String): Int {
        val fn = body11.substring(2, 7).toInt()
        val an = body11.substring(7, 11).toInt()
        return 1 + ((5 * fn + an) % 7)
    }

    private fun isPlFormula2Series(body11: String): Boolean {
        val n = body11.toLongOrNull() ?: return false
        val ranges = listOf(
            501369726L..501681125L,
            501913026L..502066375L,
            502326376L..502357425L,
            502376526L..502384225L,
            502386076L..502516975L,
            502524626L..502568525L,
            503693526L..503744925L,
            504186926L..504252075L,
            504390426L..504392425L,
            504394726L..504395725L,
            504405176L..504407025L,
            504413026L..504416625L,
            505693526L..505841325L
        )
        return ranges.any { n in it }
    }

    private fun validateRo(number: String): ValidationResult {
        if (!number.all { it.isDigit() }) return ValidationResult.Unsupported
        if (number.length < 12) return ValidationResult.InvalidLength
        if (number.length != 12) return ValidationResult.InvalidLength
        val area = number.take(2).toIntOrNull() ?: return ValidationResult.Unsupported
        return if (area in 1..42 || area in 50..99) {
            ValidationResult.Valid
        } else {
            ValidationResult.Unsupported
        }
    }
}
