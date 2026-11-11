package dev.lexicon.domain

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic wire fixtures: these tests do not establish any real model or host execution. */
class IeltsGatewayTest {
    private data class Fixture(val submit: IeltsGatewaySubmit, val health: IeltsGatewayHealth, val job: IeltsGatewayJob, val wrapper: IeltsGatewayVerifiedPackage)
    private fun fixture(): Fixture {
        val a = "🔬 The council approved solar panels."
        val b = "Public investment should support clean energy."
        val body = "$a\n\n$b"
        val paragraphs = listOf(IeltsParagraph("p1", 0, a.length, a), IeltsParagraph("p2", a.length + 2, body.length, b))
        val reading = listOf(IeltsChoiceRequest("q1"))
        val writing = IeltsWritingRequest("w2", IeltsWritingType.EXTENT_OF_AGREEMENT)
        val submit = IeltsGatewaySubmit(requestId = "request-one", articleId = "article-one", body = body, paragraphs = paragraphs, readingRequests = reading, writingTask2Request = writing)
        val request = IeltsExerciseRequest(IeltsContract.REQUEST, submit.requestId, submit.articleId, IeltsGatewayProtocol.bodyHash(body), paragraphs,
            "a".repeat(64), listOf(IeltsStyleSource("style-fixture", "b".repeat(64), "Synthetic format metadata", IeltsStyleScope.PUBLIC_SAMPLE_FORMAT)), reading, writing)
        val reference = IeltsEvidence("p1", 3, a.length, a.substring(3))
        val exercise = IeltsExercisePackage(IeltsContract.PACKAGE, request.requestId, request.articleId, request.bodyHash, IeltsExerciseJson.requestHash(request), IeltsContract.LABEL, listOf("style-fixture"),
            listOf(IeltsChoiceQuestion("q1", "What did the council approve?", IeltsChoice.entries.zip(listOf("Solar panels", "A school", "A railway", "A hospital")).map { (id, text) -> IeltsOption(id, text) }, 1,
                IeltsChoice.A, "The source names solar panels.", listOf(reference))),
            IeltsWritingTask2("w2", writing.taskType, "Public funds should prioritise clean energy. To what extent do you agree or disagree?", 250, 40,
                listOf(IeltsEvidence("p2", paragraphs[1].start, paragraphs[1].end, b))), IeltsUnavailableTask1("unavailable", "no_verified_visual"))
        val health = IeltsGatewayHealth("lexicon.ielts.health.v1", "ok", IeltsGatewayCapabilities("0.2.1-rc.5", "corpus-fixture", "c".repeat(64),
            listOf("multiple_choice", "identifying_information", "identifying_writers_views", "short_text_completion"), listOf("extent_of_agreement"), "official_public_samples_and_format_metadata"))
        val provenance = IeltsGatewayProvenance("deepseek_harness", health.capabilities.runtimeVersion, "session-generator", "session-reviewer", IeltsExerciseJson.requestHash(request),
            IeltsExerciseJson.packageHash(exercise), "d".repeat(64), health.capabilities.corpusSha256, "independent_answers_agree", false, 3, 1000,
            explanationReviewSessionId = "session-explanation-reviewer", explanationReviewSha256 = "e".repeat(64),
            explanationReview = "source_faithfulness_checked")
        val wrapper = IeltsGatewayVerifiedPackage("lexicon.ielts.verified-package.v1", request, exercise, provenance)
        val job = IeltsGatewayJob("job-" + "1".repeat(32), request.requestId, IeltsGatewayProtocol.jobInputHash(request, body, provenance.corpusSha256), IeltsGatewayJobState.READY, 3, 1, 1000, null, 1)
        return Fixture(submit, health, job, wrapper)
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid gateway material") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
        catch (_: IeltsGatewayFailure) { }
    }
    private fun check(f: Fixture, wrapper: IeltsGatewayVerifiedPackage = f.wrapper, job: IeltsGatewayJob = f.job, health: IeltsGatewayHealth = f.health, submit: IeltsGatewaySubmit = f.submit) =
        IeltsGatewayProtocol.validateVerifiedPackage(wrapper, job, submit, health)

    @Test fun endpointAcceptsOnlyAnHttpsOriginAndCanonicalizesDefaultPort() {
        assertEquals("https://gateway.example/", IeltsGatewayEndpoint.parse("https://GATEWAY.example:443").url)
        assertEquals("https://gateway.example:9443/", IeltsGatewayEndpoint.parse("https://gateway.example:9443/").url)
        for (url in listOf("http://gateway.example", "https://user:token@gateway.example", "https://gateway.example/?token=x", "https://gateway.example/#x", "https://gateway.example/prefix", "https://gateway.example/%2e%2e/", "https://gateway.example\\evil", " https://gateway.example", "https://gateway.example:0", "https://gateway.example:65536", "file:///etc/passwd")) rejects { IeltsGatewayEndpoint.parse(url) }
    }
    @Test fun exactWireTypesRoundTripWithRequiredNullJobError() {
        val f = fixture()
        assertEquals(f.submit, IeltsGatewayProtocol.decodeSubmit(IeltsGatewayProtocol.encodeSubmit(f.submit)))
        assertEquals(f.health, IeltsGatewayProtocol.decodeHealth(IeltsGatewayProtocol.encodeHealth(f.health)))
        assertEquals(f.job, IeltsGatewayProtocol.decodeJob(IeltsGatewayProtocol.encodeJob(f.job)))
        assertEquals(f.wrapper, IeltsGatewayProtocol.decodeVerifiedPackage(IeltsGatewayProtocol.encodeVerifiedPackage(f.wrapper)))
        assertTrue(IeltsGatewayProtocol.encodeJob(f.job).contains("\"error\":null"))
        assertFalse(IeltsGatewayProtocol.encodeSubmit(f.submit).contains("style_sources"))
    }
    @Test fun hashesMatchThePythonHttpAndJobContractsForTheSameSyntheticMaterial() {
        // Frozen offline using tools/ielts_http.py and tools/ielts_contract.py.
        val f = fixture()
        assertEquals("bb9fea1cc0d4f041dec5f182270b3835e6e85bb4cc92c0aeb140a216c1b2f117", IeltsExerciseJson.requestHash(f.wrapper.request))
        assertEquals("edd640f7b0d582f3e745f1ff9696b882bd2db5b300153b3511fa26e2853b336e", IeltsExerciseJson.packageHash(f.wrapper.exercisePackage))
        assertEquals("b069950eefeccfdc1e0a514c52ef6211ed47a6377616853382891cdf8290402a", f.job.inputHash)
    }
    @Test fun submittedOffsetsMustSelectTheExactBodyIncludingUnicodePairs() {
        val f = fixture(); val first = f.submit.paragraphs.first()
        IeltsGatewayProtocol.validateSubmit(f.submit)
        rejects { IeltsGatewayProtocol.validateSubmit(f.submit.copy(paragraphs = listOf(first.copy(start = 1)))) }
        rejects { IeltsGatewayProtocol.validateSubmit(f.submit.copy(body = f.submit.body.replace("solar", "lunar"))) }
        rejects { IeltsGatewayProtocol.validateSubmit(f.submit.copy(paragraphs = f.submit.paragraphs.reversed())) }
        rejects { IeltsGatewayProtocol.validateSubmit(f.submit.copy(paragraphs = listOf(first, first))) }
        rejects { IeltsGatewayProtocol.validateSubmit(f.submit.copy(body = "\uD800")) }
        rejects { IeltsGatewayProtocol.validateSubmit(f.submit.copy(body = "x".repeat(IeltsGatewayProtocol.MAX_BODY_BYTES + 1))) }
    }
    @Test fun submitRejectsExtraModelConfigurationAndMissingSchema() {
        val encoded = IeltsGatewayProtocol.encodeSubmit(fixture().submit)
        rejects { IeltsGatewayProtocol.decodeSubmit(encoded.dropLast(1) + ",\"api_key\":\"private\"}") }
        rejects { IeltsGatewayProtocol.decodeSubmit(encoded.replace("\"schema\":\"lexicon.ielts.submit.v1\",", "")) }
        rejects { IeltsGatewayProtocol.decodeSubmit(encoded.replace("\"start\":0", "\"start\":\"0\"")) }
    }
    @Test fun duplicateUnknownWrongPrimitiveAndInvalidUnicodeAreRejected() {
        val f = fixture(); val encoded = IeltsGatewayProtocol.encodeHealth(f.health)
        rejects { IeltsGatewayProtocol.decodeHealth(encoded.replace("\"status\":\"ok\"", "\"status\":\"ok\",\"status\":\"ok\"")) }
        rejects { IeltsGatewayProtocol.decodeHealth(encoded.dropLast(1) + ",\"new_field\":false}") }
        rejects { IeltsGatewayProtocol.decodeHealth(encoded.replace("\"status\":\"ok\"", "\"status\":true")) }
        rejects { IeltsGatewayProtocol.decodeHealth(encoded.replace("corpus-fixture", "\\ud800")) }
        val job = IeltsGatewayProtocol.encodeJob(f.job)
        rejects { IeltsGatewayProtocol.decodeJob(job.replace("\"revision\":3", "\"revision\":3.0")) }
        rejects { IeltsGatewayProtocol.decodeJob(job.replace("\"process_reaped\":1", "\"process_reaped\":true")) }
        rejects { IeltsGatewayProtocol.decodeJob(job.replace(",\"error\":null", "")) }
    }
    @Test fun capabilityListsAreBoundedTypedAndSupportedBeforeSubmission() {
        val f = fixture(); val caps = f.health.capabilities
        for (changed in listOf(caps.copy(supportedReadingTypes = listOf("multiple_choice", "multiple_choice")), caps.copy(supportedReadingTypes = listOf("unobserved_type")), caps.copy(corpusScope = "latest_three_year_real_exams"), caps.copy(corpusSha256 = "not-a-hash"))) rejects { IeltsGatewayProtocol.validateHealth(f.health.copy(capabilities = changed)) }
        rejects { IeltsGatewayProtocol.requireSupported(f.submit, f.health.copy(capabilities = caps.copy(supportedReadingTypes = emptyList()))) }
    }
    @Test fun verifiedPackageChecksContractAndExplicitlyRetainsPairedHostEvidenceScope() {
        val f = fixture(); val accepted = check(f)
        assertEquals("paired_host_receipt", accepted.provenanceScope)
        assertFalse(accepted.runtimeExecutedOnClient)
        assertFalse(accepted.provenance.recentThreeYearCoverage)
        assertEquals(f.wrapper.provenance.packageSha256, IeltsExerciseJson.packageHash(accepted.exercisePackage))
    }
    @Test fun runtimeAndCorpusMustMatchTheConfiguredHealth() {
        val f = fixture()
        rejects { check(f, health = f.health.copy(capabilities = f.health.capabilities.copy(runtimeVersion = "other-runtime"))) }
        rejects { check(f, health = f.health.copy(capabilities = f.health.capabilities.copy(corpusSha256 = "f".repeat(64)))) }
        rejects { check(f, wrapper = f.wrapper.copy(provenance = f.wrapper.provenance.copy(kind = "synthetic"))) }
    }
    @Test fun independentSessionsActualRequestCountAndReviewClaimCannotBeWeakened() {
        val f = fixture(); val p = f.wrapper.provenance
        for (changed in listOf(p.copy(reviewSessionId = p.generationSessionId), p.copy(realProviderRequestCount = 1), p.copy(realProviderRequestCount = -1), p.copy(semanticReview = "structure_only"), p.copy(recentThreeYearCoverage = true), p.copy(reviewSha256 = "unknown"), p.copy(generationSessionId = "../trace"))) rejects { check(f, wrapper = f.wrapper.copy(provenance = changed)) }
    }
    @Test fun explanationsRequireAThirdDistinctSessionAndAnExactReviewClaim() {
        val f = fixture(); val p = f.wrapper.provenance
        for (changed in listOf(
            p.copy(explanationReviewSessionId = p.generationSessionId),
            p.copy(explanationReviewSessionId = p.reviewSessionId),
            p.copy(explanationReviewSessionId = "../trace"),
            p.copy(explanationReviewSessionId = null),
            p.copy(explanationReviewSha256 = null),
            p.copy(explanationReviewSha256 = "not-a-hash"),
            p.copy(explanationReviewSha256 = "A".repeat(64)),
            p.copy(explanationReview = null),
            p.copy(explanationReview = "independent_answers_agree"),
            p.copy(explanationReview = "failed"),
            p.copy(realProviderRequestCount = 2),
            p.copy(realProviderRequestCount = 17),
        )) rejects { check(f, wrapper = f.wrapper.copy(provenance = changed)) }
        assertEquals(16L, check(f, wrapper = f.wrapper.copy(provenance = p.copy(realProviderRequestCount = 16))).provenance.realProviderRequestCount)
    }
    @Test fun legacyArtifactDecodesWithoutInventingExplanationReviewButCannotBecomeANewImport() {
        val f = fixture()
        val legacy = f.wrapper.copy(provenance = f.wrapper.provenance.copy(realProviderRequestCount = 2,
            explanationReviewSessionId = null, explanationReviewSha256 = null, explanationReview = null))
        val encoded = IeltsGatewayProtocol.encodeVerifiedPackage(legacy)
        assertFalse(encoded.contains("explanation_review"))
        val restored = IeltsGatewayProtocol.decodeVerifiedPackage(encoded)
        assertEquals(legacy, restored)
        assertNull(restored.provenance.explanationReviewSessionId)
        assertNull(restored.provenance.explanationReviewSha256)
        assertNull(restored.provenance.explanationReview)
        assertEquals(encoded, IeltsGatewayProtocol.encodeVerifiedPackage(restored))
        rejects { check(f, wrapper = restored) }
    }
    @Test fun partialNullAndMistypedExplanationProvenanceNeverDecodesAsReviewed() {
        val f = fixture()
        val root = Json.parseToJsonElement(IeltsGatewayProtocol.encodeVerifiedPackage(f.wrapper)).jsonObject
        val provenance = root.getValue("provenance").jsonObject
        val keys = listOf("explanation_review_session_id", "explanation_review_sha256", "explanation_review")
        fun encoded(changed: JsonObject) = JsonObject(root.toMutableMap().apply { put("provenance", changed) }).toString()
        for (key in keys) {
            rejects { IeltsGatewayProtocol.decodeVerifiedPackage(encoded(JsonObject(provenance - key))) }
            for (value in listOf(JsonNull, JsonPrimitive(3), JsonPrimitive(false))) {
                rejects { IeltsGatewayProtocol.decodeVerifiedPackage(encoded(JsonObject(provenance.toMutableMap().apply { put(key, value) }))) }
            }
        }
        rejects { IeltsGatewayProtocol.decodeVerifiedPackage(encoded(JsonObject(provenance.toMutableMap().apply { keys.forEach { put(it, JsonNull) } }))) }
    }
    @Test fun clientRecomputesRequestAndPackageHashesRatherThanTrustingWrapperStrings() {
        val f = fixture()
        rejects { check(f, wrapper = f.wrapper.copy(provenance = f.wrapper.provenance.copy(requestSha256 = "e".repeat(64)))) }
        rejects { check(f, wrapper = f.wrapper.copy(provenance = f.wrapper.provenance.copy(packageSha256 = "e".repeat(64)))) }
        val changed = f.wrapper.exercisePackage.copy(readingQuestions = f.wrapper.exercisePackage.readingQuestions.map { (it as IeltsChoiceQuestion).copy(answer = IeltsChoice.B) })
        rejects { check(f, wrapper = f.wrapper.copy(exercisePackage = changed)) }
    }
    @Test fun jobInputHashIsBoundToOriginalFullBodyRequestAndCorpus() {
        val f = fixture()
        rejects { check(f, job = f.job.copy(inputHash = "e".repeat(64))) }
        rejects { check(f, submit = f.submit.copy(body = f.submit.body + " Unselected additional material.")) }
        rejects { check(f, submit = f.submit.copy(requestId = "request-other")) }
        rejects { check(f, job = f.job.copy(requestId = "request-other")) }
        assertNotEquals(f.job.inputHash, IeltsGatewayProtocol.jobInputHash(f.wrapper.request, f.submit.body, "f".repeat(64)))
    }
    @Test fun publicationRequiresReadyReapedJobAndExactSourceRanges() {
        val f = fixture()
        rejects { check(f, job = f.job.copy(state = IeltsGatewayJobState.REVIEWING)) }
        rejects { check(f, job = f.job.copy(processReaped = 0)) }
        val q = f.wrapper.exercisePackage.readingQuestions[0] as IeltsChoiceQuestion
        val incorrect = f.wrapper.exercisePackage.copy(readingQuestions = listOf(q.copy(sourceRefs = q.sourceRefs.map { it.copy(start = it.start + 1) })))
        rejects { check(f, wrapper = f.wrapper.copy(exercisePackage = incorrect, provenance = f.wrapper.provenance.copy(packageSha256 = IeltsExerciseJson.packageHash(incorrect)))) }
    }
    @Test fun wrapperUnknownFieldsAndMalformedProvenanceCannotBeSilentlyDropped() {
        val f = fixture(); val encoded = IeltsGatewayProtocol.encodeVerifiedPackage(f.wrapper)
        rejects { IeltsGatewayProtocol.decodeVerifiedPackage(encoded.dropLast(1) + ",\"trace\":\"private\"}") }
        rejects { IeltsGatewayProtocol.decodeVerifiedPackage(encoded.replace("\"real_provider_request_count\":3", "\"real_provider_request_count\":\"3\"")) }
        rejects { IeltsGatewayProtocol.decodeVerifiedPackage(encoded.replace("\"recent_three_year_coverage\":false", "\"recent_three_year_coverage\":false,\"recent_three_year_coverage\":false")) }
    }
    @Test fun typedUncertaintyPreservesRequestIdWithoutPrivateExceptionCause() {
        val error = IeltsGatewaySubmissionUncertain("request-one")
        assertEquals("request-one", error.requestId); assertEquals("submit_outcome_unknown", error.message); assertNull(error.cause)
        val connection = IeltsGatewayConnection("https://host.example/", true, fixture().health)
        assertFalse(connection.toString().contains("token"))
    }
}
