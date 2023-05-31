/*
 * Copyright (C) 2017 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.plugins.scanners.scancode

import com.fasterxml.jackson.databind.JsonNode

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import org.ossreviewtoolkit.model.CopyrightFinding
import org.ossreviewtoolkit.model.Issue
import org.ossreviewtoolkit.model.LicenseFinding
import org.ossreviewtoolkit.model.ScanSummary
import org.ossreviewtoolkit.model.Severity
import org.ossreviewtoolkit.model.TextLocation
import org.ossreviewtoolkit.model.createAndLogIssue
import org.ossreviewtoolkit.model.mapLicense
import org.ossreviewtoolkit.model.utils.associateLicensesWithExceptions
import org.ossreviewtoolkit.utils.common.textValueOrEmpty
import org.ossreviewtoolkit.utils.spdx.SpdxConstants.LICENSE_REF_PREFIX
import org.ossreviewtoolkit.utils.spdx.toSpdxId

import org.semver4j.Semver

const val MAX_SUPPORTED_OUTPUT_FORMAT_MAJOR_VERSION = 2

internal val SCANCODE_TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss.n").withZone(ZoneId.of("UTC"))

private data class LicenseMatch(
    val expression: String,
    val startLine: Int,
    val endLine: Int,
    val score: Float
)

private val LICENSE_REF_PREFIX_SCAN_CODE = "$LICENSE_REF_PREFIX${ScanCode.SCANNER_NAME.lowercase()}-"

// Note: The "(File: ...)" part in the patterns below is actually added by our own getRawResult() function.
private val UNKNOWN_ERROR_REGEX = Regex(
    "(ERROR: for scanner: (?<scanner>\\w+):\n)?" +
            "ERROR: Unknown error:\n.+\n(?<error>\\w+Error)(:|\n)(?<message>.*) \\(File: (?<file>.+)\\)",
    RegexOption.DOT_MATCHES_ALL
)

private val TIMEOUT_ERROR_REGEX = Regex(
    "(ERROR: for scanner: (?<scanner>\\w+):\n)?" +
            "ERROR: Processing interrupted: timeout after (?<timeout>\\d+) seconds. \\(File: (?<file>.+)\\)"
)

/**
 * Generate a summary from the given raw ScanCode [result] using [verificationCode] metadata. This variant can be used
 * if the result is not read from a local file. If [parseExpressions] is true, license findings are preferably parsed as
 * license expressions.
 */
internal fun generateSummary(result: JsonNode, parseExpressions: Boolean = true): ScanSummary {
    val header = result["headers"].single()

    val issues = mutableListOf<Issue>()
    val outputFormatVersion = header["output_format_version"]?.textValue()?.let { Semver(it) }
    if (outputFormatVersion != null) {
        val maxSupportedVersion = Semver.coerce(MAX_SUPPORTED_OUTPUT_FORMAT_MAJOR_VERSION.toString())

        if (outputFormatVersion > maxSupportedVersion && !outputFormatVersion.isApiCompatible(maxSupportedVersion)) {
            issues += ScanCode.createAndLogIssue(
                source = ScanCode.SCANNER_NAME,
                message = "The output format version $outputFormatVersion exceeds the supported major version " +
                        "$MAX_SUPPORTED_OUTPUT_FORMAT_MAJOR_VERSION. Results may be incomplete or incorrect.",
                severity = Severity.WARNING
            )
        }
    }

    val startTimestamp = header["start_timestamp"].textValue()
    val endTimestamp = header["end_timestamp"].textValue()

    val startTime = SCANCODE_TIMESTAMP_FORMATTER.parse(startTimestamp).query(Instant::from)
    val endTime = SCANCODE_TIMESTAMP_FORMATTER.parse(endTimestamp).query(Instant::from)

    return ScanSummary(
        startTime = startTime,
        endTime = endTime,
        licenseFindings = getLicenseFindings(result, parseExpressions),
        copyrightFindings = getCopyrightFindings(result),
        issues = issues + getIssues(result)
    )
}

private fun getInputPath(result: JsonNode): String {
    val header = result["headers"].single()
    val input = header["options"]["input"]
    val path = input.takeUnless { it.isArray } ?: input.single()
    return path.textValue().let { "$it/" }
}

/**
 * Get the license findings from the given [result]. If [parseExpressions] is true and license expressions are contained
 * in the result, these are preferred over separate license findings. Otherwise, only separate license findings are
 * parsed.
 */
private fun getLicenseFindings(result: JsonNode, parseExpressions: Boolean): Set<LicenseFinding> {
    val licenseFindings = mutableListOf<LicenseFinding>()

    val files = result["files"]?.asSequence().orEmpty().filter { it["type"].textValue() == "file" }

    files.flatMapTo(licenseFindings) { file ->
        val licenses = file["licenses"]?.asSequence().orEmpty()

        licenses.groupBy(
            keySelector = {
                LicenseMatch(
                    (if (parseExpressions) it["matched_rule"]["license_expression"] else it["key"]).textValue(),
                    it["start_line"].intValue(),
                    it["end_line"].intValue(),
                    it["score"].floatValue()
                )
            },
            valueTransform = {
                it["key"].textValue() to getSpdxLicenseId(it)
            }
        ).map { (licenseMatch, replacements) ->
            val spdxLicenseExpression = licenseMatch.expression.mapLicense(replacements.toMap())

            LicenseFinding(
                license = spdxLicenseExpression,
                location = TextLocation(
                    path = file["path"].textValue(),
                    startLine = licenseMatch.startLine,
                    endLine = licenseMatch.endLine
                ),
                score = licenseMatch.score
            )
        }
    }

    return associateLicensesWithExceptions(licenseFindings)
}

/**
 * Get the SPDX license id (or a fallback) for a license finding.
 */
private fun getSpdxLicenseId(license: JsonNode): String {
    // There is a bug in ScanCode 3.0.2 that returns an empty string instead of null for licenses unknown to SPDX.
    val idFromSpdxKey = license["spdx_license_key"].textValueOrEmpty().toSpdxId(allowPlusSuffix = true)

    // For regular SPDX IDs, return early here.
    if (idFromSpdxKey.isNotEmpty()) return idFromSpdxKey

    // At this point the ID is either empty or a non-ScanCode SPDX LicenseRef, so fall back to building an ID based
    // on the ScanCode-specific "key".
    val idFromKey = license["key"].textValue().toSpdxId(allowPlusSuffix = true)

    return "$LICENSE_REF_PREFIX_SCAN_CODE$idFromKey"
}

/**
 * Get the copyright findings from the given [result].
 */
private fun getCopyrightFindings(result: JsonNode): Set<CopyrightFinding> {
    val copyrightFindings = mutableSetOf<CopyrightFinding>()

    val header = result["headers"].single()
    val outputFormatVersion = header["output_format_version"]?.textValue()?.let { Semver(it) }
    val copyrightKeyName = if (outputFormatVersion == null || outputFormatVersion < Semver("2.0.0")) {
        "value"
    } else {
        "copyright"
    }

    val files = result["files"]?.asSequence().orEmpty()

    files.flatMapTo(copyrightFindings) { file ->
        val path = file["path"].textValue()

        val copyrights = file["copyrights"]?.asSequence().orEmpty()
        copyrights.map { copyright ->
            CopyrightFinding(
                statement = copyright[copyrightKeyName].textValue(),
                location = TextLocation(
                    path = path,
                    startLine = copyright["start_line"].intValue(),
                    endLine = copyright["end_line"].intValue()
                )
            )
        }
    }

    return copyrightFindings
}

/**
 * Get the list of [Issue]s for scanned files.
 */
private fun getIssues(result: JsonNode): List<Issue> {
    val input = getInputPath(result)
    return result["files"]?.flatMap { file ->
        val path = file["path"].textValue().removePrefix(input)
        file["scan_errors"].map {
            Issue(
                source = ScanCode.SCANNER_NAME,
                message = "${it.textValue()} (File: $path)"
            )
        }
    }.orEmpty()
}

/**
 * Map messages about timeout errors to a more compact form. Return true if solely timeout errors occurred, return false
 * otherwise.
 */
internal fun mapTimeoutErrors(issues: MutableList<Issue>): Boolean {
    if (issues.isEmpty()) return false

    var onlyTimeoutErrors = true

    val mappedIssues = issues.map { fullError ->
        val match = TIMEOUT_ERROR_REGEX.matchEntire(fullError.message)
        if (match?.groups?.get("timeout")?.value == ScanCode.TIMEOUT.toString()) {
            val file = match.groups["file"]!!.value
            fullError.copy(
                message = "ERROR: Timeout after ${ScanCode.TIMEOUT} seconds while scanning file '$file'."
            )
        } else {
            onlyTimeoutErrors = false
            fullError
        }
    }

    issues.clear()
    issues += mappedIssues.distinctBy { it.message }

    return onlyTimeoutErrors
}

/**
 * Map messages about unknown issues to a more compact form. Return true if solely memory errors occurred, return false
 * otherwise.
 */
internal fun mapUnknownIssues(issues: MutableList<Issue>): Boolean {
    if (issues.isEmpty()) return false

    var onlyMemoryErrors = true

    val mappedIssues = issues.map { fullError ->
        UNKNOWN_ERROR_REGEX.matchEntire(fullError.message)?.let { match ->
            val file = match.groups["file"]!!.value
            val error = match.groups["error"]!!.value
            if (error == "MemoryError") {
                fullError.copy(message = "ERROR: MemoryError while scanning file '$file'.")
            } else {
                onlyMemoryErrors = false
                val message = match.groups["message"]!!.value.trim()
                fullError.copy(message = "ERROR: $error while scanning file '$file' ($message).")
            }
        } ?: run {
            onlyMemoryErrors = false
            fullError
        }
    }

    issues.clear()
    issues += mappedIssues.distinctBy { it.message }

    return onlyMemoryErrors
}