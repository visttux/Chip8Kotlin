package com.visttux.chip8emulator

import android.graphics.Bitmap
import android.graphics.Color
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent.*
import android.view.View
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import java.util.*
import kotlin.experimental.and

class MainActivity : AppCompatActivity() {
    private var opcode: Short = 0x0000
    private val memory: ByteArray = ByteArray(4096)
    private val registers: ByteArray = ByteArray(16) //from V0 to VF
    private var index: Int = 0x0000
    private var pc: Int = ROM_ADDRESS
    private var delay_timer: Byte = 0b00000000
    private val sound_timer: Byte = 0b00000000
    private var stack: ShortArray = ShortArray(16)
    private var sp: Int = 0x0000 //stack pointer
    private val keypad: ByteArray = ByteArray(16)
    private lateinit var bitmap: Bitmap
    private val fontSet: ByteArray = intArrayOf(
            0xF0, 0x90, 0x90, 0x90, 0xF0, // 0
            0x20, 0x60, 0x20, 0x20, 0x70, // 1
            0xF0, 0x10, 0xF0, 0x80, 0xF0, // 2
            0xF0, 0x10, 0xF0, 0x10, 0xF0, // 3
            0x90, 0x90, 0xF0, 0x10, 0x10, // 4
            0xF0, 0x80, 0xF0, 0x10, 0xF0, // 5
            0xF0, 0x80, 0xF0, 0x90, 0xF0, // 6
            0xF0, 0x10, 0x20, 0x40, 0x40, // 7
            0xF0, 0x90, 0xF0, 0x90, 0xF0, // 8
            0xF0, 0x90, 0xF0, 0x10, 0xF0, // 9
            0xF0, 0x90, 0xF0, 0x90, 0x90, // A
            0xE0, 0x90, 0xE0, 0x90, 0xE0, // B
            0xF0, 0x80, 0x80, 0x80, 0xF0, // C
            0xE0, 0x90, 0x90, 0x90, 0xE0, // D
            0xF0, 0x80, 0xF0, 0x80, 0xF0, // E
            0xF0, 0x80, 0xF0, 0x80, 0x80  // F
    ).foldIndexed(ByteArray(80)) { i, a, v -> a.apply { set(i, v.toByte()) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        executeGame()
    }

    private fun executeGame() {
        launch {
            loadFont()
            loadDisplay()
            loadRom()
            configureInputs()
            while (true) {
                emulateCycle()
                delay(5)
            }
        }
    }

    private fun configureInputs() {
        configureKey(left, 0x5)
        configureKey(right, 0x6)
        configureKey(turn, 0x4)
    }

    private fun configureKey(view: View, index: Int) {
        view.setOnTouchListener { v, event ->
            when (event?.action) {
                ACTION_DOWN -> keypad[index] = 1
                ACTION_UP -> keypad[index] = 0
            }
            v?.onTouchEvent(event) ?: true
        }
    }

    /* We use a bitmap of 2048px (64x32) as display */
    private fun loadDisplay() {
        bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.RGB_565)
        screen.setImageBitmap(bitmap)
    }

    private fun loadRom() {
        resources.assets.open("TETRIS").read(memory, ROM_ADDRESS, memory.size - ROM_ADDRESS)
    }

    private fun loadFont() {
        for (i in 0 until FONT_ADDRESS) {
            memory[i] = fontSet[i]
        }
    }

    private fun emulateCycle() {
        //Fetch opcode from memory (merge two bytes)
        opcode = ((memory[pc].toInt() and 0xFF) shl 8 or (memory[pc + 1].toInt() and 0xFF)).toShort()
        pc += 2
        // Decode Opcode
        when (opcode and 0xF000.toShort()) {
            0x0000.toShort() -> {
                when (opcode) {
                    0x00EE.toShort() -> returnFromSubroutine()
                    else -> Log.d("Unknown opcode:", Integer.toHexString(opcode.toInt()))
                }
            }
            0x1000.toShort() -> jumpToAddress()
            0x2000.toShort() -> jumpToSubroutine()
            0x3000.toShort() -> skipIfEquals()
            0x4000.toShort() -> skipIfNotEquals()
            0x6000.toShort() -> setRegister()
            0x7000.toShort() -> addToRegister()
            0x8000.toShort() -> {
                when (opcode and 0x000F) {
                    0x0000.toShort() -> assign()
                    else -> Log.d("Unknown opcode:", Integer.toHexString(opcode.toInt()))
                }
            }

            0x9000.toShort() -> skipIfRegistersNotEquals()
            0xA000.toShort() -> setIndex()
            0xC000.toShort() -> rand()
            0xD000.toShort() -> draw()
            0xE000.toShort() -> {
                when (opcode and 0x00FF) {
                    0x00A1.toShort() -> skipIfNotPressed()
                    0x009E.toShort() -> skipIfPressed()
                    else -> Log.d("Unknown opcode:", Integer.toHexString(opcode.toInt()))
                }
            }
            0xF000.toShort() -> {
                when (opcode and 0x00FF) {
                    0x001E.toShort() -> addToIndex()
                    0x0015.toShort() -> setDelayTimer()
                    0x0007.toShort() -> setRegisterToTimerValue()
                    else -> Log.d("Unknown opcode:", Integer.toHexString(opcode.toInt()))
                }
            }

            else -> Log.d("Unknown opcode:", Integer.toHexString(opcode.toInt()))
        }

        // Update timers
        if (delay_timer > 0) {
            delay_timer = delay_timer.dec()
        }
    }

    //0xFX07: Sets VX to the value of the delay timer.
    private fun setRegisterToTimerValue() {
        val x = (opcode and 0x0F00).toInt() ushr 8
        registers[x] = delay_timer
    }

    /* 0xEX9E: Skips the next instruction if the key stored in VX is pressed. */
    private fun skipIfPressed() {
        val x = (opcode and 0x0F00).toInt() ushr 8
        if (keypad[registers[x].toInt()] > 0) {
            pc += 2
        }
    }

    /* 0xEX15: Skips the next instruction if the key stored in VX is not pressed. */
    private fun skipIfNotPressed() {
        val x = (opcode and 0x0F00).toInt() ushr 8
        if (keypad[registers[x].toInt()] == 0.toByte()) {
            pc += 2
        }
    }

    /*0x9XY0: Skips the next instruction if VX doesn't equal VY. */
    private fun skipIfRegistersNotEquals() {
        val x = (opcode and 0x0F00).toInt() ushr 8
        val y = (opcode and 0x00F0).toInt() ushr 4
        if (registers[x] == registers[y]) {
            pc += 2
        }
    }

    /* 0x8XY0: Sets VX to the value of VY. */
    private fun assign() {
        val x = (opcode and 0x0F00).toInt() ushr 8
        val y = (opcode and 0x00F0).toInt() ushr 4
        registers[x] = registers[y]
    }

    /* 0xFX15: Sets the delay timer to VX. */
    private fun setDelayTimer() {
        val x = (opcode and 0x0F00).toInt() ushr 8
        delay_timer = registers[x]
    }

    /* 0xFX1E: Adds VX to index. */
    private fun addToIndex() {
        val x = (opcode and 0x0F00).toInt() ushr 8
        index += registers[x]
    }

    /* 0x4XNN Skips the next instruction if VX doesn't equal NN. */
    private fun skipIfNotEquals() {
        val value = (opcode and 0x00FF).toByte()
        val x = (opcode and 0x0F00).toInt() ushr 8
        if (registers[x] != value) {
            pc += 2
        }
    }

    /* 0xCXNN: Sets VX to the result of a bitwise and operation on a random number (Typically: 0 to 255) and NN. */
    private fun rand() {
        val x = (opcode and 0x0F00).toInt() ushr 8
        registers[x] = Random().nextInt().toByte() and (opcode and 0x00FF).toByte()
    }

    /* 0x1NNN Jumps to address NNN. */
    private fun jumpToAddress() {
        pc = (opcode and 0x0FFF).toInt()
    }

    /* 0x3XNN: Skips the next instruction if VX equals NN */
    private fun skipIfEquals() {
        val value = (opcode and 0x00FF).toByte()
        val x = (opcode and 0x0F00).toInt() ushr 8
        if (registers[x] == value) {
            pc += 2
        }
    }

    /* 0xDXYN Draw 8xN sprite at I to VX, VY; VF = 1 if collision else 0 */
    private fun draw() {
        registers[0xF] = 0 //Collision flag (VF) to 0 as we don't know yet
        val x = (opcode and 0x0F00).toInt() ushr 8
        val y = (opcode and 0x00F0).toInt() ushr 4
        val n = (opcode and 0x000F).toInt()
        var sprite: Byte
        val posX = registers[x].toInt()
        val posY = registers[y].toInt()

        for (row in 0 until n) {
            sprite = memory[index + row]
            for (col in 0 until 8) {
                if (sprite.toInt() and ((0b10000000 shr col)) > 0) { //Check bit per bit
                    val pixel = bitmap.getPixel(posX + col, posY + row)
                    if (pixel == Color.WHITE) { //Check if it's already painted to erase and put VF to true
                        bitmap.setPixel(posX + col, posY + row, Color.BLACK)
                        registers[0xF] = 1
                    } else {
                        bitmap.setPixel(posX + col, posY + row, Color.WHITE)
                    }
                    runOnUiThread({ screen.invalidate() })
                }
            }
        }
    }

    /* 0x00EE return from previous subroutine */
    private fun returnFromSubroutine() {
        pc = stack[sp].toInt()
        sp = sp.dec()
    }

    /* 0x6XNN: Sets VX to NN. */
    private fun setRegister() {
        val x = (opcode and 0x0F00).toInt() ushr 8
        registers[x] = (opcode and 0x00FF).toByte()
    }

    /* 0x7XNN: Adds NN to VX. (Carry flag is not changed) */
    private fun addToRegister() {
        val x = (opcode and 0x0F00).toInt() ushr 8
        registers[x] = registers[x].plus(opcode and 0x00FF).toByte()
    }

    /* 0x2NNN: Calls subroutine at NNN (copy on stack) */
    private fun jumpToSubroutine() {
        sp = sp.inc()
        stack[sp] = pc.toShort()
        pc = (opcode and 0x0FFF).toInt()
    }

    /* 0xANNN: Sets index to the address NNN. */
    private fun setIndex() {
        index = (opcode and 0x0FFF).toInt()
    }

    companion object {
        const val FONT_ADDRESS = 0x0050
        const val ROM_ADDRESS = 0x0200
    }
}
