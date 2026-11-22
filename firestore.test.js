const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validScriptPayload(uid, createdAt = new Date(Date.now() - 5000), overrides = {}) {
  return {
    userId: uid,
    title: "Browser agents share one cookie jar",
    ideaSpark: "Headless browser agents share one cookie jar across tabs.",
    targetDurationSec: 30,
    beats: [
      {
        id: "beat_1",
        index: 0,
        spokenText: "Headless browser agents share one cookie jar by default.",
        durationSec: 6,
        cut: false,
      },
    ],
    stageConfig: {
      backdropPresetId: "volt_truss_globe",
      stage4Mode: "photo_head_lora",
      chyronHeadline: "Agent Session Security",
      chyronSubline: "Aircheck Report",
      cornerBugText: "AIRCHECK",
      showBrandBug: true,
    },
    voiceSource: "ENROLLED_VOICE_CLONE",
    createdAt,
    updatedAt: createdAt,
    ...overrides,
  };
}

function validPresetPayload(uid, createdAt = new Date(Date.now() - 5000), overrides = {}) {
  return {
    userId: uid,
    name: "Morning Desk",
    backdropPresetId: "volt_truss_globe",
    targetDurationSec: 30,
    voiceSource: "ENROLLED_VOICE_CLONE",
    createdAt,
    updatedAt: createdAt,
    ...overrides,
  };
}

function sampleJobPayload(uid) {
  const now = new Date(Date.now() - 3000);
  return {
    userId: uid,
    clipId: "clip_deadbeef_123456",
    scriptId: "script_1",
    title: "Browser agents share one cookie jar",
    status: "READY",
    progressFraction: 1.0,
    stageDetail: "Broadcast master ready",
    hashes: {
      voiceSha8: "1111aaaa",
      alignSha8: "2222bbbb",
      lipsyncSha8: "3333cccc",
      finalSha8: "4444dddd",
    },
    voiceSha: "a".repeat(64),
    finalSha: "b".repeat(64),
    waveformPeaks: [0.12, 0.55, 0.89, 0.42],
    alignedWords: [{ word: "Headless", startMs: 0, endMs: 420 }],
    remoteFinalUrl: "https://storage.googleapis.com/aircheck/final_4444dddd.mp4",
    createdAt: now,
    updatedAt: now,
  };
}

function samplePersonaPayload() {
  return {
    headModelId: "lora_head_alice_v2",
    voiceId: "el_pvc_alice_01",
    defaultPhotoSha: "c".repeat(64),
    trainingJobId: "train_0123456789",
    trainingStatus: "READY",
    trainingError: "",
    updatedAt: new Date(Date.now() - 3000),
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Default-deny blocks unauthenticated and authenticated access to undeclared paths", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();

  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).get());
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).set({ isAdmin: true }));
  await assertFails(aliceDb.collection("admin").doc("config").set({ role: "admin" }));
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("billing").doc("plan").set({ tier: "pro" })
  );
});

test("Unauthenticated user cannot read or write scripts, presets, jobs, or profile", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();

  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("scripts").get());
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("scripts")
      .doc("script_1")
      .set(validScriptPayload(ALICE_UID))
  );
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("presets").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("jobs").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("jobs").doc("job_1").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("profile").doc("persona").get()
  );
});

test("Owner can create, get, list, update (with optional sourceUrl & voiceSelectionId), and delete own script", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const scriptsCol = aliceDb.collection("users").doc(ALICE_UID).collection("scripts");
  const ref = scriptsCol.doc("script_1");

  // Create with required fields only
  await assertSucceeds(ref.set(validScriptPayload(ALICE_UID)));
  await assertSucceeds(ref.get());
  await assertSucceeds(scriptsCol.get());

  // Partial update with optional sourceUrl, voiceSelectionId, and MIC_VOICEOVER
  await assertSucceeds(
    ref.update({
      title: "Tighter headline",
      sourceUrl: "https://example.com/news/agent-cookies",
      targetDurationSec: 15,
      voiceSource: "MIC_VOICEOVER",
      voiceSelectionId: "voice_override_42",
      updatedAt: new Date(Date.now() - 2000),
    })
  );

  // Update clearing sourceUrl to empty string and voiceSelectionId to null
  await assertSucceeds(
    ref.update({
      sourceUrl: "",
      voiceSelectionId: null,
      voiceSource: "ENROLLED_VOICE_CLONE",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Create directly with full Android FirestoreSyncRepository payload shape
  const fullRef = scriptsCol.doc("script_full-2");
  await assertSucceeds(
    fullRef.set(
      validScriptPayload(ALICE_UID, new Date(Date.now() - 4000), {
        sourceUrl: "http://example.org/story",
        voiceSelectionId: "el_pvc_voice_1",
        beats: [
          {
            id: "beat_a",
            index: 0,
            spokenText: "Opening claim.",
            durationSec: 5,
            cut: false,
            mentionTag: "@desk",
            depthTextTag: "40% CUT",
            anchorWord: "claim",
            evidenceSource: "Q3 Report",
            sfxTag: "whoosh",
            phoneticOverride: "klaym",
            bRollPrompt: "Server rack telemetry close-up",
          },
        ],
        stageConfig: {
          backdropPresetId: "cobalt_truss_globe",
          customBackdropPrompt: "Dark newsroom",
          photoSha: "a".repeat(64),
          normalizedPhotoSha: "",
          stage4Mode: "saved_loop_latentsync",
          chyronHeadline: "BREAKING",
          chyronSubline: "Aircheck Desk",
          cornerBugText: "AIRCHECK",
          showBrandBug: true,
        },
      })
    )
  );

  // Worst-case Android FirestoreSyncRepository.syncProject() update: all 9 mutable fields + 40 full beats + full stageConfig
  const maxBeats = Array.from({ length: 40 }, (_, i) => ({
    id: `beat_${i}`,
    index: i,
    spokenText: "Spoken claim with verification details.",
    durationSec: 5,
    cut: false,
    mentionTag: "@desk",
    depthTextTag: "40% CUT",
    anchorWord: "claim",
    evidenceSource: "Q3 Report",
    sfxTag: "whoosh",
    phoneticOverride: "klaym",
    bRollPrompt: "Server rack telemetry close-up",
  }));
  await assertSucceeds(
    fullRef.update({
      title: "Updated full broadcast script",
      ideaSpark: "Updated spark with URL",
      sourceUrl: "https://example.org/updated-story",
      targetDurationSec: 60,
      beats: maxBeats,
      stageConfig: {
        backdropPresetId: "volt_truss_globe",
        customBackdropPrompt: "Updated obsidian newsdesk",
        photoSha: "d".repeat(64),
        normalizedPhotoSha: "e".repeat(64),
        stage4Mode: "libreclip_crop",
        chyronHeadline: "UPDATED HEADLINE",
        chyronSubline: "Updated Aircheck Desk",
        cornerBugText: "LIVE",
        showBrandBug: false,
      },
      voiceSource: "MIC_VOICEOVER",
      voiceSelectionId: "el_pvc_voice_2",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertSucceeds(ref.delete());
  await assertSucceeds(fullRef.delete());
});

test("Scripts reject invalid IDs, legacy/invalid enums, missing/extra fields, immutable tampering, and DoS sizes", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const scriptsCol = aliceDb.collection("users").doc(ALICE_UID).collection("scripts");
  const createdAt = new Date(Date.now() - 5000);
  const ref = scriptsCol.doc("script_valid");
  await assertSucceeds(ref.set(validScriptPayload(ALICE_UID, createdAt)));

  // Invalid document IDs
  await assertFails(scriptsCol.doc("bad.id").set(validScriptPayload(ALICE_UID)));
  await assertFails(scriptsCol.doc("bad id").set(validScriptPayload(ALICE_UID)));
  await assertFails(scriptsCol.doc("a".repeat(129)).set(validScriptPayload(ALICE_UID)));

  // Legacy v1 voice sources must fail on create and update
  await assertFails(
    scriptsCol
      .doc("script_legacy1")
      .set(validScriptPayload(ALICE_UID, createdAt, { voiceSource: "MALE_TTS_DRY_RUN" }))
  );
  await assertFails(
    scriptsCol
      .doc("script_legacy2")
      .set(validScriptPayload(ALICE_UID, createdAt, { voiceSource: "VOICE_CLONE" }))
  );
  await assertFails(
    ref.update({ voiceSource: "MALE_TTS_DRY_RUN", updatedAt: new Date(Date.now() - 1000) })
  );
  await assertFails(
    ref.update({ voiceSource: "UNKNOWN_MODE", updatedAt: new Date(Date.now() - 1000) })
  );

  // Invalid targetDurationSec (enum + type juggling)
  await assertFails(ref.update({ targetDurationSec: 20, updatedAt: new Date(Date.now() - 1000) }));
  await assertFails(
    ref.update({ targetDurationSec: "30", updatedAt: new Date(Date.now() - 1000) })
  );

  // Ownership spoofing on create and immutable tampering on update
  await assertFails(
    scriptsCol
      .doc("script_spoof")
      .set(validScriptPayload(ALICE_UID, createdAt, { userId: BOB_UID }))
  );
  await assertFails(
    ref.update({ userId: BOB_UID, updatedAt: new Date(Date.now() - 1000) })
  );
  await assertFails(
    ref.update({
      createdAt: new Date(Date.now() - 2000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Extra ghost fields on create and update
  await assertFails(
    scriptsCol.doc("script_ghost").set({
      ...validScriptPayload(ALICE_UID, createdAt),
      id: "script_ghost",
    })
  );
  await assertFails(
    ref.update({ extraField: "evil", updatedAt: new Date(Date.now() - 1000) })
  );

  // Missing required field on create and removing required field on update (overwrite)
  const missingTitle = validScriptPayload(ALICE_UID, createdAt);
  delete missingTitle.title;
  await assertFails(scriptsCol.doc("script_missing").set(missingTitle));
  await assertFails(ref.set(missingTitle));

  // Future timestamp and updatedAt < createdAt
  await assertFails(
    scriptsCol
      .doc("script_future")
      .set(validScriptPayload(ALICE_UID, new Date(Date.now() + 60000)))
  );
  await assertFails(
    ref.update({ updatedAt: new Date(createdAt.getTime() - 10000) })
  );

  // Resource exhaustion / size bounds + URL validation on update (Update Bypass prevention)
  await assertFails(
    ref.update({ title: "t".repeat(181), updatedAt: new Date(Date.now() - 1000) })
  );
  await assertFails(
    ref.update({ ideaSpark: "s".repeat(1001), updatedAt: new Date(Date.now() - 1000) })
  );
  await assertFails(
    ref.update({
      sourceUrl: "https://example.com/" + "a".repeat(490),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    ref.update({ sourceUrl: "javascript:alert(1)", updatedAt: new Date(Date.now() - 1000) })
  );
  await assertFails(
    ref.update({ sourceUrl: null, updatedAt: new Date(Date.now() - 1000) })
  );
  await assertFails(
    ref.update({ voiceSelectionId: "v".repeat(129), updatedAt: new Date(Date.now() - 1000) })
  );

  // Beats list limit (> 40) and nested schema validation
  const tooManyBeats = Array.from({ length: 41 }, (_, i) => ({
    id: `b_${i}`,
    index: i,
    spokenText: "Line",
    durationSec: 4,
    cut: false,
  }));
  await assertFails(
    ref.update({ beats: tooManyBeats, updatedAt: new Date(Date.now() - 1000) })
  );
  await assertFails(
    ref.update({
      beats: [{ id: "b_0", index: 0, spokenText: "x".repeat(601), durationSec: 5, cut: false }],
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // StageConfig strict allowlist & enum checks
  await assertFails(
    ref.update({
      stageConfig: { backdropPresetId: "volt_truss_globe", injectedKey: "x".repeat(500) },
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    ref.update({
      stageConfig: { stage4Mode: "invalid_stage4" },
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("Owner CRUD and strict validation on /presets", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const presetsCol = aliceDb.collection("users").doc(ALICE_UID).collection("presets");
  const presetRef = presetsCol.doc("preset_1");

  await assertSucceeds(presetRef.set(validPresetPayload(ALICE_UID)));
  await assertSucceeds(presetRef.get());
  await assertSucceeds(presetsCol.get());

  // Valid update to MIC_VOICEOVER
  await assertSucceeds(
    presetRef.update({
      name: "Evening Desk",
      targetDurationSec: 45,
      voiceSource: "MIC_VOICEOVER",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject legacy voice sources, invalid duration, oversized name, extra field, immutable tampering
  await assertFails(
    presetsCol
      .doc("preset_legacy")
      .set(validPresetPayload(ALICE_UID, new Date(Date.now() - 5000), { voiceSource: "MALE_TTS_DRY_RUN" }))
  );
  await assertFails(
    presetRef.update({ voiceSource: "VOICE_CLONE", updatedAt: new Date(Date.now() - 500) })
  );
  await assertFails(
    presetRef.update({ name: "", updatedAt: new Date(Date.now() - 500) })
  );
  await assertFails(
    presetRef.update({ name: "n".repeat(81), updatedAt: new Date(Date.now() - 500) })
  );
  await assertFails(
    presetRef.update({ extra: true, updatedAt: new Date(Date.now() - 500) })
  );
  await assertFails(
    presetRef.update({ userId: BOB_UID, updatedAt: new Date(Date.now() - 500) })
  );
  await assertFails(
    presetRef.update({ createdAt: new Date(Date.now() - 500), updatedAt: new Date(Date.now() - 500) })
  );

  // Cross-user isolation on presets
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("presets").doc("preset_1").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("presets").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("presets")
      .doc("preset_1")
      .delete()
  );

  await assertSucceeds(presetRef.delete());
});

test("Jobs collection allows owner get & list ONLY and denies all client writes & cross-user access", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const adminDb = context.firestore();
    await adminDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("jobs")
      .doc("job_abc123456789")
      .set(sampleJobPayload(ALICE_UID));
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const aliceJobs = aliceDb.collection("users").doc(ALICE_UID).collection("jobs");
  const aliceJobRef = aliceJobs.doc("job_abc123456789");

  // Owner can get (valid ID) and list own jobs
  await assertSucceeds(aliceJobRef.get());
  await assertSucceeds(aliceJobs.get());

  // Invalid jobId fails get
  await assertFails(aliceJobs.doc("bad.job").get());

  // Owner CANNOT create, update, or delete jobs (Cloud Functions v2 Admin SDK only)
  await assertFails(aliceJobs.doc("job_forged").set(sampleJobPayload(ALICE_UID)));
  await assertFails(
    aliceJobRef.update({
      status: "READY",
      remoteFinalUrl: "https://evil.example/fake.mp4",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(aliceJobRef.delete());

  // Cross-user isolation: Bob cannot read or write Alice's jobs
  const bobViewOfAliceJobs = bobDb.collection("users").doc(ALICE_UID).collection("jobs");
  await assertFails(bobViewOfAliceJobs.doc("job_abc123456789").get());
  await assertFails(bobViewOfAliceJobs.get());
  await assertFails(bobViewOfAliceJobs.doc("job_bob_inject").set(sampleJobPayload(ALICE_UID)));
  await assertFails(
    bobViewOfAliceJobs.doc("job_abc123456789").update({ status: "FAILED" })
  );
  await assertFails(bobViewOfAliceJobs.doc("job_abc123456789").delete());
});

test("Profile collection allows owner get ONLY on docId == 'persona', denies list, all client writes, and cross-user access", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const adminDb = context.firestore();
    const profileCol = adminDb.collection("users").doc(ALICE_UID).collection("profile");
    await profileCol.doc("persona").set(samplePersonaPayload());
    await profileCol.doc("internal_secrets").set({ apiKey: "secret" });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const aliceProfileCol = aliceDb.collection("users").doc(ALICE_UID).collection("profile");
  const alicePersonaRef = aliceProfileCol.doc("persona");

  // Owner can get /users/{uid}/profile/persona
  await assertSucceeds(alicePersonaRef.get());

  // Owner CANNOT get any other docId under /profile
  await assertFails(aliceProfileCol.doc("internal_secrets").get());
  await assertFails(aliceProfileCol.doc("other").get());

  // Owner CANNOT list /profile
  await assertFails(aliceProfileCol.get());

  // Owner CANNOT create, update, or delete /profile/persona (Admin SDK only)
  await assertFails(aliceProfileCol.doc("persona").set(samplePersonaPayload()));
  await assertFails(
    alicePersonaRef.update({
      headModelId: "spoofed_lora_id",
      voiceId: "spoofed_voice_id",
      trainingStatus: "READY",
    })
  );
  await assertFails(alicePersonaRef.delete());
  await assertFails(aliceProfileCol.doc("custom").set({ foo: "bar" }));

  // Cross-user isolation: Bob cannot read or write Alice's /profile/persona
  const bobViewOfAliceProfile = bobDb.collection("users").doc(ALICE_UID).collection("profile");
  await assertFails(bobViewOfAliceProfile.doc("persona").get());
  await assertFails(bobViewOfAliceProfile.get());
  await assertFails(bobViewOfAliceProfile.doc("persona").set(samplePersonaPayload()));
  await assertFails(bobViewOfAliceProfile.doc("persona").update({ trainingStatus: "FAILED" }));
  await assertFails(bobViewOfAliceProfile.doc("persona").delete());
});

test("Cross-user isolation blocks Bob from reading, creating, updating, or deleting Alice's scripts", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("scripts")
      .doc("alice_script")
      .set(validScriptPayload(ALICE_UID))
  );

  const bobViewOfAliceScripts = bobDb.collection("users").doc(ALICE_UID).collection("scripts");
  await assertFails(bobViewOfAliceScripts.doc("alice_script").get());
  await assertFails(bobViewOfAliceScripts.get());
  await assertFails(bobViewOfAliceScripts.doc("bob_inject").set(validScriptPayload(BOB_UID)));
  await assertFails(
    bobViewOfAliceScripts
      .doc("alice_script")
      .update({ title: "Hijacked", updatedAt: new Date(Date.now() - 1000) })
  );
  await assertFails(bobViewOfAliceScripts.doc("alice_script").delete());
});
