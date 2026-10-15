package com.marcmayol.rodet.flow

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.marcmayol.rodet.R
import com.marcmayol.rodet.model.Field
import com.marcmayol.rodet.model.Issue
import com.marcmayol.rodet.model.IssueCode
import com.marcmayol.rodet.reader.WriteProblem
import com.marcmayol.rodet.tags.DisabledReason
import com.marcmayol.rodet.tags.ProfileError
import com.marcmayol.rodet.tags.ProfileException
import com.marcmayol.rodet.tags.Profiles

@Composable
fun disabledText(reason: DisabledReason): String = stringResource(
    when (reason) {
        DisabledReason.NO_NFC -> R.string.disabled_no_nfc
        DisabledReason.NO_MIFARE_CLASSIC -> R.string.disabled_no_mifare
        DisabledReason.NO_NFC_V -> R.string.disabled_no_nfcv
        DisabledReason.FORMAT_NOT_WRITABLE -> R.string.disabled_not_writable
    },
)

@Composable
fun issueText(issue: Issue): String {
    val a = issue.args
    return when (issue.code) {
        IssueCode.MISSING_MATERIAL -> stringResource(R.string.issue_missing_material)
        IssueCode.MISSING_COLOR -> stringResource(R.string.issue_missing_color)
        IssueCode.INVALID_COLOR_HEX -> stringResource(R.string.issue_invalid_hex)
        IssueCode.OUT_OF_PHYSICAL_RANGE -> stringResource(R.string.issue_out_of_range, "${a[1]}", "${a[2]}")
        IssueCode.RANGE_INVERTED -> stringResource(R.string.issue_inverted)
        IssueCode.TEMP_UNUSUAL_FOR_MATERIAL -> stringResource(R.string.issue_temp_unusual, "${a[1]}", "${a[2]}", "${a[3]}")
        IssueCode.DIAMETER_NOT_NOMINAL -> stringResource(R.string.issue_diameter)
        IssueCode.WEIGHT_IMPLAUSIBLE -> stringResource(R.string.issue_weight)
    }
}

@Composable
private fun fieldName(f: Field): String = stringResource(
    when (f) {
        Field.NOZZLE_MIN, Field.NOZZLE_MAX -> R.string.f_nozzle
        Field.BED_MIN, Field.BED_MAX -> R.string.f_bed
        Field.COLOR_HEX, Field.COLOR_NAME -> R.string.f_color
        Field.NET_WEIGHT -> R.string.f_weight
        Field.DIAMETER -> R.string.f_diameter
        else -> R.string.f_material
    },
)

@Composable
fun profileErrorText(e: ProfileException): String = when (e.error) {
    ProfileError.MISSING_KEY -> stringResource(R.string.perr_missing_key)
    ProfileError.WRONG_KEY -> stringResource(R.string.perr_wrong_key)
    ProfileError.MISSING_FIELD -> stringResource(R.string.perr_missing_field, fieldName(e.args.first() as Field))
    ProfileError.UNSUPPORTED_MATERIAL -> stringResource(
        R.string.perr_unsupported_material,
        "${e.args.firstOrNull()}",
        e.alternativeProfileId?.let { Profiles.byId(it)?.name } ?: "OpenSpool",
    )
    ProfileError.TOO_LARGE -> stringResource(R.string.perr_too_large)
    ProfileError.INVALID_UID -> stringResource(R.string.perr_invalid_uid)
    ProfileError.NOT_WRITABLE -> stringResource(R.string.disabled_not_writable)
    ProfileError.INVALID_SPEC -> stringResource(R.string.review_fix_first)
}

/** Que fallo y que hacer, en dos frases. */
@Composable
fun failureText(f: WriteStatus.Failed, tagType: String): Pair<String, String> = when {
    f.existing != null -> stringResource(R.string.fail_has_data, f.existing) to stringResource(R.string.fail_has_data_todo)
    f.profileError != null -> profileErrorText(f.profileError) to stringResource(R.string.fail_back_to_review)
    else -> when (f.problem) {
        WriteProblem.WRONG_TECH -> stringResource(R.string.fail_wrong_tech) to stringResource(R.string.fail_wrong_tech_todo, tagType)
        WriteProblem.READ_ONLY -> stringResource(R.string.fail_read_only) to stringResource(R.string.fail_other_sticker)
        WriteProblem.TOO_SMALL -> stringResource(R.string.fail_too_small) to stringResource(R.string.fail_wrong_tech_todo, tagType)
        WriteProblem.NO_KEY_OPENS -> stringResource(R.string.fail_no_key) to stringResource(R.string.fail_other_sticker)
        WriteProblem.LOST_TAG -> stringResource(R.string.fail_lost) to stringResource(R.string.fail_lost_todo)
        WriteProblem.VERIFY_FAILED -> stringResource(R.string.fail_verify) to stringResource(R.string.fail_lost_todo)
        WriteProblem.UNKNOWN, null -> stringResource(R.string.fail_unknown) to stringResource(R.string.fail_lost_todo)
    }
}
