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

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validScriptPayload(uid, createdAt = new Date(Date.now() - 5000)) {
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
      chyronHeadline: "Agent Session Security",
      chyronSubline: "Aircheck Report",
      showBrandBug: true,
    },
    voiceSource: "MALE_TTS_DRY_RUN",
    createdAt,
    updatedAt: createdAt,
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

test("Unauthenticated user cannot read or write scripts", async () => {
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
});

test("Owner can create, list, partial update, and delete own script", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ref = aliceDb.collection("users").doc(ALICE_UID).collection("scripts").doc("script_1");

  await assertSucceeds(ref.set(validScriptPayload(ALICE_UID)));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("scripts").get());

  await assertSucceeds(
    ref.update({
      title: "Tighter headline",
      targetDurationSec: 15,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertSucceeds(ref.delete());
});

test("Rejects immutable field tampering, extra ghost fields, future timestamps, and invalid enums", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ref = aliceDb.collection("users").doc(ALICE_UID).collection("scripts").doc("script_2");
  await assertSucceeds(ref.set(validScriptPayload(ALICE_UID)));

  // Immutable createdAt change must fail
  await assertFails(
    ref.update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Extra ghost field on create must fail
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("scripts")
      .doc("script_ghost")
      .set({
        ...validScriptPayload(ALICE_UID),
        id: "script_ghost",
      })
  );

  // Future timestamp must fail
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("scripts")
      .doc("script_future")
      .set(validScriptPayload(ALICE_UID, new Date(Date.now() + 60000)))
  );

  // Invalid voice source must fail
  await assertFails(
    ref.update({
      voiceSource: "UNKNOWN_MODE",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("Cross-user isolation blocks Bob from reading or modifying Alice scripts and presets", async () => {
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

  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("scripts").doc("alice_script").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("scripts").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("scripts")
      .doc("alice_script")
      .update({ title: "Hijacked", updatedAt: new Date(Date.now() - 1000) })
  );

  // Owner preset lifecycle
  const presetRef = aliceDb.collection("users").doc(ALICE_UID).collection("presets").doc("preset_1");
  await assertSucceeds(
    presetRef.set({
      userId: ALICE_UID,
      name: "Morning Desk",
      backdropPresetId: "volt_truss_globe",
      targetDurationSec: 30,
      voiceSource: "MALE_TTS_DRY_RUN",
      createdAt: new Date(Date.now() - 5000),
      updatedAt: new Date(Date.now() - 5000),
    })
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("presets").doc("preset_1").get()
  );
});
