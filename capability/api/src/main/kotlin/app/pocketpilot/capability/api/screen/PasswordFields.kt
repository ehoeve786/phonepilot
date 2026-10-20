package app.pocketpilot.capability.api.screen

import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException

/** Typing into a password field is refused on every backend, so an AI app cannot enter or submit a password. */
fun passwordFieldRefused() =
    ToolException(
        ToolErrorCode.INVALID_ARGUMENTS,
        "That is a password field. PocketPilot does not type into password fields",
        "Ask the phone's owner to enter the password",
    )
