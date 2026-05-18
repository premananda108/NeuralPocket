package ua.pp.prema.NeuralPocket

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine

fun test(engine: Engine) {
    val methods = Conversation::class.java.methods
    for (m in methods) {
        println(m.name)
    }
}
