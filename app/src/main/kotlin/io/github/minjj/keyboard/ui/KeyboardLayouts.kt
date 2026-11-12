package io.github.minjj.keyboard.ui

import io.github.minjj.keyboard.hangul.Dubeolsik
import io.github.minjj.keyboard.ime.*
import io.github.minjj.keyboard.settings.KeyboardSettings

object KeyboardLayouts {
    private val secondarySymbols = "qwertyuiopasdfghjklzxcvbnm".toList().zip(listOf(
        "+", "×", "÷", "=", "/", "_", "<", ">", "[", "]",
        "!", "@", "#", "$", "%", "&", "*", "(", ")", "-", "'", "\"", ":", ";", ",", "?"
    )).toMap()
    private fun chars(text: String, letter: Boolean = false, korean: Boolean = false, shift: Boolean = false) = text.map {
        KeySpec(if (letter && korean) Dubeolsik.map(it, shift).toString() else if (letter && shift) it.uppercase() else it.toString(),
            KeyCommand.CHARACTER, it.toString(), letter = letter)
    }
    private fun command(label: String, command: KeyCommand, weight: Float = 1f) = KeySpec(label, command, weight = weight)

    fun rows(controller: KeyboardController, profile: EditorProfile, enter: String, split: Boolean = false,
        extended: Boolean = false, numberRow: Boolean = true, settings: KeyboardSettings = KeyboardSettings()): List<List<KeySpec>> {
        val delete = command("⌫", KeyCommand.DELETE, 1.4f)
        val action = command(enter, KeyCommand.ENTER, 1.5f)
        if (profile.layout != InputLayout.TEXT) {
            val empty = command("", KeyCommand.SPACER)
            val bottom = when (profile.layout) {
                InputLayout.PHONE -> chars("+0*#")
                InputLayout.DATETIME -> chars("/0:-")
                else -> listOf(
                    if (profile.signed) chars("-").single() else empty,
                    chars("0").single(),
                    if (profile.decimal) chars(".").single() else empty,
                )
            }
            val extraColumn = List(bottom.size - 3) { empty }
            return listOf(
                chars("123") + extraColumn + delete.copy(weight = 1f),
                chars("456") + extraColumn + command("한/영", KeyCommand.LANGUAGE),
                chars("789") + extraColumn + command("공백", KeyCommand.SPACE),
                bottom + action.copy(weight = 1f),
            )
        }
        if (controller.symbolsPage != 0) return symbolRows(controller, action, split, extended).map { row ->
            row.map { key -> settings.symbolOverrides[key.symbolSlot]?.let { key.copy(label = it, value = it) } ?: key }
        }
        val controls = mutableListOf(command("!#1", KeyCommand.SYMBOLS, 1.3f),
            command("한/영", KeyCommand.LANGUAGE, 1.2f),
            KeySpec(if (profile.email) "@" else if (profile.uri) "/" else ",", KeyCommand.CHARACTER),
            command(if (controller.korean) "한국어" else "English", KeyCommand.SPACE, 3.6f),
            KeySpec(".", KeyCommand.CHARACTER), action)
        if (split) {
            controls.add(3, controls[3].copy()) // Space on each thumb's half, retaining punctuation.
            controls[4] = controls[4].copy(splitBefore = true)
        }
        val bottom = if (extended) extendedControls(controller, profile, split) else controls
        if (extended) {
            fun divide(keys: List<KeySpec>, index: Int): List<KeySpec> = if (!split) keys else
                keys.mapIndexed { i, key -> if (i == index) key.copy(splitBefore = true) else key }
            val rows = mutableListOf<List<KeySpec>>()
            if (numberRow) rows += divide(chars("1234567890") + command("Del", KeyCommand.DELETE_FORWARD), 5)
            rows += divide(chars("qwertyuiop", true, controller.korean, controller.uppercase) + command("⌫", KeyCommand.DELETE), 5)
            rows += divide(listOf(command("", KeyCommand.SPACER, if (split) 0.5f else 0.6f)) +
                chars("asdfghjkl", true, controller.korean, controller.uppercase) + action.copy(weight = if (split) 1.5f else 1.4f), 6)
            val shift = command(if (controller.shift == ShiftState.LOCKED) "⇪" else "⇧", KeyCommand.SHIFT)
            rows += divide(listOf(if (split) shift.copy(weight = 1.5f) else shift) + chars("zxcvbnm", true, controller.korean, controller.uppercase) + chars(",.") + shift, 5)
            rows += bottom
            return rows
        }
        return (if (numberRow) listOf(chars("1234567890")) else emptyList()) + listOf(
            chars("qwertyuiop", true, controller.korean, controller.uppercase),
            (if (split) listOf(command("", KeyCommand.SPACER, 0.5f)) else emptyList()) +
                chars("asdfghjkl", true, controller.korean, controller.uppercase).mapIndexed { index, key ->
                    if (split && index == 5) key.copy(splitBefore = true) else key
                },
            listOf(command(if (controller.shift == ShiftState.LOCKED) "⇪" else "⇧", KeyCommand.SHIFT, 1.4f)) +
                chars("zxcvbnm", true, controller.korean, controller.uppercase) + delete,
            controls)
    }

    private fun symbolRows(controller: KeyboardController, enter: KeySpec, split: Boolean, extended: Boolean): List<List<KeySpec>> {
        // Assign identities before splitting, so custom values follow the same physical slots.
        return defaultSymbolRows(controller, enter, extended).mapIndexed { row, keys ->
            keys.mapIndexed { column, key ->
                if (key.command == KeyCommand.CHARACTER) key.copy(
                    symbolSlot = "${if (extended) "extended" else "normal"}:${controller.symbolsPage}:$row:$column"
                ) else if (split && extended && row == 2) key.copy(
                    weight = if (key.command == KeyCommand.SPACER) 1f else 1.5f
                ) else key
            }
        }.mapIndexed { row, keys ->
            if (!split) keys else if (row == 4) {
                val space = keys.indexOfFirst { it.command == KeyCommand.SPACE }
                keys.toMutableList().apply { add(space + 1, keys[space].copy(splitBefore = true)) }
            } else if (extended) {
                val boundary = if (row == 2) 6 else 5
                keys.mapIndexed { index, key -> if (index == boundary) key.copy(splitBefore = true) else key }
            } else keys
        }
    }

    private fun defaultSymbolRows(controller: KeyboardController, enter: KeySpec, extended: Boolean): List<List<KeySpec>> {
        val first = controller.symbolsPage == 1
        val numbers = chars("1234567890")
        val operators = chars(if (first) "+×÷=/_<>[]" else "`~\\|{}€£¥" + if (extended) "$" else "₩")
        val page = command("${controller.symbolsPage}/2", KeyCommand.MORE_SYMBOLS)
        val letters = command(if (controller.korean) "가" else "ABC", KeyCommand.LETTERS)
        val space = command(if (controller.korean) "한국어" else "English", KeyCommand.SPACE, if (extended) 7f else 5f)
        val spaces = listOf(space)
        if (!extended) {
            return listOf(
                numbers,
                operators,
                chars(if (first) "!@#$%^&*()" else "•○●□■♤♡◇♧°"),
                listOf(page.copy(weight = 1.5f)) + chars(if (first) "-'\":;,?" else "☆▪¤《》¡¿") +
                    command("⌫", KeyCommand.DELETE, 1.5f),
                listOf(letters.copy(weight = 1.5f)) + chars(",") + spaces + chars(".") + enter,
            )
        }
        val controls = listOf(
            command("한/영", KeyCommand.LANGUAGE),
            letters,
        ) + spaces +
            listOf(command("‹", KeyCommand.CURSOR_LEFT), command("›", KeyCommand.CURSOR_RIGHT))
        return listOf(
            numbers + command("Del", KeyCommand.DELETE_FORWARD),
            operators + command("⌫", KeyCommand.DELETE),
            listOf(command("", KeyCommand.SPACER, 0.6f)) +
                chars(if (first) "@#₩%^&*()" else "•○●□■♤♡◇♧") + enter.copy(weight = 1.4f),
            listOf(page) + chars(if (first) "-'\":;,?!." else "☆▪¤《》¡¿°.") + page,
            controls,
        )
    }

    private fun extendedControls(controller: KeyboardController, profile: EditorProfile, split: Boolean): List<KeySpec> {
        val controls = mutableListOf(command("한/영", KeyCommand.LANGUAGE),
            command("!#1", KeyCommand.SYMBOLS))
        if (profile.email || profile.uri) controls += KeySpec(if (profile.email) "@" else "/", KeyCommand.CHARACTER)
        val space = command(if (controller.korean) "한국어" else "English", KeyCommand.SPACE, 6.8f)
        controls += space
        if (split) controls += space.copy(splitBefore = true)
        controls += command("‹", KeyCommand.CURSOR_LEFT)
        controls += command("›", KeyCommand.CURSOR_RIGHT)
        return controls
    }

    fun secondaryId(key: KeySpec, korean: Boolean): String? {
        if (key.command != KeyCommand.CHARACTER) return null
        return key.symbolSlot?.let { "symbol:$it" } ?: "${if (korean) "ko" else "en"}:${key.value}"
    }

    fun secondary(key: KeySpec, korean: Boolean, settings: KeyboardSettings = KeyboardSettings()): String? {
        val id = secondaryId(key, korean) ?: return null
        settings.secondaryOverrides[id]?.let { return it.ifEmpty { null } }
        if (key.symbolSlot != null) return null
        if (key.value == ",") return "!"
        if (key.value == ".") return "?"
        if (!key.letter || key.value.length != 1) return null
        val char = key.value.single()
        if (korean) {
            val shifted = Dubeolsik.map(char, true)
            if (shifted != Dubeolsik.map(char, false)) return shifted?.toString()
        }
        return secondarySymbols[char]
    }
}
