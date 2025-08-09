
package com.example.aiscriber

data class Finding(val name: String, var certainty: Double = 0.7)
data class FollowUp(
    val id: String,
    val text: String,
    val targets: List<String>,
    var asked: Boolean = false,
    var answered: Boolean = false
)
data class Diff(val label: String, val score: Double, val rationale: List<String>)
data class Order(val name: String, val kind: String)
data class Soap(val S: String, val O: String, val A: String, val P: String)

object MedicalKB {
    val dx = mapOf(
        "influenza" to Dx(
            "Influenza", pri = -1.2,
            findings = mapOf(
                "fever" to Weights(1.0, -0.3),
                "cough" to Weights(0.7, -0.2),
                "myalgias" to Weights(0.8, -0.2),
                "sore throat" to Weights(0.4, -0.1),
            ),
            orders = listOf("Influenza NAAT (LOINC 94500-6)")
        ),
        "pneumonia" to Dx(
            "Community-acquired pneumonia", pri = -1.6,
            findings = mapOf(
                "fever" to Weights(0.6, -0.2),
                "productive cough" to Weights(1.0, -0.3),
                "pleuritic chest pain" to Weights(0.8, -0.2),
                "tachypnea" to Weights(0.7, -0.2),
                "focal crackles" to Weights(1.1, -0.3),
            ),
            orders = listOf("CXR PA/LAT", "CBC with diff", "Pulse oximetry")
        ),
        "mononucleosis" to Dx(
            "Infectious mononucleosis", pri = -2.0,
            findings = mapOf(
                "sore throat" to Weights(0.9, -0.2),
                "fatigue" to Weights(0.7, -0.2),
                "posterior LAD" to Weights(1.1, -0.3),
                "splenomegaly" to Weights(0.9, -0.3),
            ),
            orders = listOf("Monospot/EBV serology", "Avoid contact sports if splenomegaly")
        ),
    )

    val questions = mapOf(
        "fever" to "Have you had a measured fever? How high and how many days?",
        "cough" to "Is the cough dry or productive? Any blood or sputum color?",
        "myalgias" to "Do you have body aches or chills?",
        "sore throat" to "Any trouble swallowing or swollen glands?",
        "pleuritic chest pain" to "Does it hurt more with a deep breath?",
        "tachypnea" to "Any shortness of breath or breathing fast?",
        "focal crackles" to "Has anyone mentioned abnormal lung sounds?"
    )
}

data class Weights(val pos: Double, val neg: Double)
data class Dx(val label: String, val pri: Double, val findings: Map<String, Weights>, val orders: List<String>)

class ScribeEngine {
    private val transcript = mutableListOf<Pair<String,String>>() // speaker, text
    private val findings = mutableListOf<Finding>()

    fun addLine(rawText: String) {
        val speaker = autoSpeaker(rawText)
        transcript += speaker to rawText.trim()
        inferFindings()
    }

    fun uiState(): UiState {
        val diffs = computeDiffs()
        val followups = computeFollowups(diffs)
        val orders = computeOrders(diffs)
        val soap = makeSoap(diffs)
        return UiState(
            transcript = transcript.toList(),
            followups = followups,
            diffs = diffs,
            orders = orders,
            soap = soap
        )
    }

    private fun autoSpeaker(t: String): String {
        val s = t.trim().lowercase()
        return if (s.endsWith("?") || s.startsWith("do ") || s.startsWith("did ") || s.startsWith("are ")
            || s.startsWith("is ") || s.startsWith("have ") || s.startsWith("has ") || s.startsWith("what ")
            || s.startsWith("when ") || s.startsWith("where ") || s.startsWith("how ")) "provider" else "patient"
    }

    private fun inferFindings() {
        val text = transcript.joinToString(" ") { it.second }.lowercase()
        MedicalKB.questions.keys.forEach { key ->
            if (text.contains(key) && findings.none { it.name == key }) {
                findings += Finding(key, 0.7)
            }
        }
    }

    private fun sigmoid(x: Double) = 1.0 / (1.0 + kotlin.math.exp(-x))

    private fun computeDiffs(): List<Diff> {
        return MedicalKB.dx.values.map { dx ->
            var logit = dx.pri
            val evidence = mutableListOf<String>()
            findings.forEach { f ->
                dx.findings[f.name]?.let { w ->
                    logit += if (f.certainty > 0.5) w.pos else w.neg * 0.5
                    evidence += f.name
                }
            }
            Diff(dx.label, sigmoid(logit), evidence)
        }.sortedByDescending { it.score }
    }

    private fun computeFollowups(diffs: List<Diff>): List<FollowUp> {
        val need = linkedSetOf<String>()
        diffs.take(2).forEach { d ->
            val dx = MedicalKB.dx.values.first { it.label == d.label }
            dx.findings.keys.forEach { f -> if (findings.none { it.name == f }) need += f }
        }
        return need.map { n -> FollowUp(id = n, text = MedicalKB.questions[n] ?: "Tell me more about $n?", targets = listOf(n)) }
    }

    private fun computeOrders(diffs: List<Diff>): List<Order> {
        val set = linkedSetOf<String>()
        diffs.take(2).forEach { d ->
            val dx = MedicalKB.dx.values.first { it.label == d.label }
            set += dx.orders
        }
        return set.map { n -> Order(n, if (n.contains("CXR", true)) "imaging" else "lab") }
    }

    private fun makeSoap(diffs: List<Diff>): Soap {
        val s = transcript.filter { it.first == "patient" }.joinToString(" ") { it.second }
        val o = findings.joinToString("\n") { "- ${it.name} (c=${"%.2f".format(it.certainty)})" }
        val a = diffs.take(3).joinToString("\n") { "${it.label} (${(it.score*100).toInt()}%)" }
        val p = computeOrders(diffs).joinToString("\n") { "- ${it.name}" }
        return Soap(s, o, a, p)
    }
}

data class UiState(
    val transcript: List<Pair<String,String>>,
    val followups: List<FollowUp>,
    val diffs: List<Diff>,
    val orders: List<Order>,
    val soap: Soap
)
