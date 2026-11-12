/** Offline policy tests using the installed Cordis loader and real ToolRuntime.
 * No model, external network, Android, credential or user configuration access.
 * Run: node tools/test_harness_ielts_plugin.mjs
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync, mkdtempSync, existsSync, realpathSync, statSync, renameSync, symlinkSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import * as plugin from './harness_ielts_plugin.mjs';

const SELF = fileURLToPath(import.meta.url);
const PLUGIN = fileURLToPath(new URL('./harness_ielts_plugin.mjs', import.meta.url));
const ROOT = resolve(dirname(SELF), '..');
const ELECTRON = '/Applications/DeepSeek Harness.app/Contents/MacOS/DeepSeek Harness';
const RUNTIME_ROOT = '/Applications/DeepSeek Harness.app/Contents/Resources/app.asar/dsh/node_modules';
const PYTHON = '/usr/bin/python3';
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const spec = path => ({path, sha256: sha(readFileSync(path))});
const cleanEnv = {PATH: '/usr/bin:/bin', LANG: 'en_US.UTF-8', LC_ALL: 'en_US.UTF-8', ELECTRON_RUN_AS_NODE: '1'};

if (!process.versions.electron) {
  const child = spawnSync(ELECTRON, ['--expose-internals', SELF], {cwd: ROOT, env: cleanEnv, stdio: 'inherit'});
  process.exit(child.status ?? 1);
}

const artifactRoot = join(ROOT, 'dist/validation-ielts-harness-2026-11-08');
mkdirSync(artifactRoot, {recursive: true});
const scratch = mkdtempSync(join(artifactRoot, 'plugin-selftest-'));
const base = pathToFileURL(join(RUNTIME_ROOT, '@deepseek-ai/')).href;
const {Context} = await import(base + 'cordis/lib/index.js');
const {default: Loader} = await import(base + 'cordis-plugin-loader/lib/index.js');
const {default: ToolRuntime} = await import(base + 'dsh-tools/lib/index.js');
const {createScope} = await import(base + 'dsh-scope/lib/index.js');
const {default: SessionStore} = await import(base + 'dsh-session/lib/index.js');
const {createToolResultMessage} = await import(base + 'dsh-llm/lib/index.js');

// Synthetic protocol data is imported from the existing independent contract
// tests. It is never labelled a model-generated or semantically verified run.
const fixtureProcess = spawnSync(PYTHON, ['-I', '-B', '-c', `
import json,sys
sys.path.insert(0,sys.argv[1])
from tools.test_ielts_candidate import explanation_fixture
print(json.dumps(explanation_fixture(),ensure_ascii=False))
`, ROOT], {env: cleanEnv, encoding: 'utf8', maxBuffer: 1048576});
assert.equal(fixtureProcess.status, 0, 'offline fixtures must load');
const [request, candidate, review, explanationReview] = JSON.parse(fixtureProcess.stdout);
const WRITING_INSTRUCTION = 'To what extent do you agree or disagree with this statement?';
const SYNTHETIC_WRITING_DRAFT = Object.freeze({background: 'The writer argues that public investment should support clean energy.',
  target_statement: 'Public funds should prioritise clean energy.'});
const corpusPath = join(ROOT, 'dist/validation-ielts-corpus-2026-11-08/corpus-01/reference.json');
assert(existsSync(corpusPath), 'build the frozen corpus before the plugin selftest');
const cases = [];
let caseNumber = 0;
function file(path, value) { writeFileSync(path, JSON.stringify(value, null, 2), {mode: 0o600}); return spec(path); }
// This authored adapter is confined to synthetic tests. Production never
// derives a target from an existing free-form prompt or a stored artifact.
function generationInput(value = candidate, draft = SYNTHETIC_WRITING_DRAFT) {
  const raw = structuredClone(value);
  delete raw.writing_task2.prompt;
  raw.writing_task2.prompt_draft = structuredClone(draft);
  return raw;
}
function compiledGeneration(value) {
  const compiled = structuredClone(value); const draft = compiled.writing_task2.prompt_draft;
  delete compiled.writing_task2.prompt_draft;
  compiled.writing_task2.prompt = [...(draft.background ? [draft.background] : []), draft.target_statement, WRITING_INSTRUCTION].join('\n\n');
  return compiled;
}
function requestWithParagraphs(texts) {
  let start = 0;
  const paragraphs = texts.map((text, index) => {
    const paragraph = {paragraph_id: `p${index + 1}`, start, end: start + text.length, text};
    start = paragraph.end + 2;
    return paragraph;
  });
  return {...structuredClone(request), body_hash: sha(texts.join('\n\n')), paragraphs};
}
function locator(ref) { return {paragraph_id: ref.paragraph_id, quote: ref.quote}; }
function compilerFixture(mode = 'generation') {
  const raw = mode === 'generation' ? generationInput() : structuredClone(mode === 'review' ? review : explanationReview);
  const compiled = mode === 'generation' ? compiledGeneration(raw) : structuredClone(raw);
  if (mode === 'explanation_review') {
    for (const item of raw.explanation_reviews) item.source_refs = item.source_refs.map(locator);
    return {raw, compiled};
  }
  const items = mode === 'generation' ? 'reading_questions' : 'reading_reviews';
  const writing = mode === 'generation' ? 'writing_task2' : 'writing_task2_review';
  const quote = '🔬 The council approved solar panels.';
  const context = {paragraph_id: 'p1', start: request.paragraphs[0].start,
    end: request.paragraphs[0].start + quote.length, quote};
  raw[items][3].source_refs = [locator(context)];
  compiled[items][3].source_refs.unshift(context);
  raw[writing].source_refs = raw[writing].source_refs.map(locator);
  if (mode === 'review') {
    const refs = raw.reading_reviews[2].not_given_review.paragraph_reviews[1].context_refs;
    raw.reading_reviews[2].not_given_review.paragraph_reviews[1].context_refs = refs.map(locator);
  }
  return {raw, compiled};
}
function fixture(mode = 'generation', options = {}) {
  const directory = join(scratch, `case-${++caseNumber}`); mkdirSync(directory);
  const inputs = options.continuation ? join(directory, 'inputs') : directory;
  const state = options.continuation ? join(directory, 'state') : directory;
  if (options.continuation) { mkdirSync(inputs); mkdirSync(state); }
  const files = {
    contract: spec(join(ROOT, 'tools/ielts_contract.py')),
    corpusModule: spec(join(ROOT, 'tools/ielts_corpus.py')),
    corpus: spec(corpusPath), request: file(join(directory, 'request.json'), options.request ?? request),
  };
  if (mode !== 'generation') {
    files.reviewModule = spec(join(ROOT, 'tools/ielts_review.py'));
    files.package = file(join(directory, 'package.json'), options.package ?? candidate);
  }
  if (mode === 'explanation_review') {
    files.blindReview = file(join(directory, 'blind-review.json'), options.blindReview ?? review);
    const source = join(ROOT, 'tools/ielts_explanation_review.py');
    if (options.copyExplanation) {
      const copied = join(directory, 'pinned_explanation.py'); writeFileSync(copied, readFileSync(source));
      files.explanationReviewModule = spec(copied);
    } else files.explanationReviewModule = spec(source);
  }
  if (options.strictSchema) {
    const source = join(ROOT, 'tools/ielts_json_schema.py');
    if (options.copySchema) {
      const copied = join(directory, 'pinned_schema.py'); writeFileSync(copied, readFileSync(source));
      files.schemaModule = spec(copied);
    } else files.schemaModule = spec(source);
  }
  if (options.strictSchema || options.candidateArtifact) {
    const source = join(ROOT, 'tools/ielts_candidate.py');
    if (options.copyCompiler) {
      const copied = join(directory, 'pinned_candidate.py'); writeFileSync(copied, readFileSync(source));
      files.candidateModule = spec(copied);
    } else files.candidateModule = spec(source);
  }
  if (options.probeEnvironment || options.delay) {
    let code = readFileSync(files.contract.path, 'utf8');
    if (options.probeEnvironment) code += '\nimport os\nassert "LEXICON_SELFTEST_SECRET" not in os.environ\nassert "PYTHONPATH" not in os.environ\nassert "DEEPSEEK_API_KEY" not in os.environ\n';
    if (options.delay) code += '\n_original_validate_package=validate_package\ndef validate_package(request, package):\n    if package.get("_delay"):\n        import time\n        time.sleep(2)\n    return _original_validate_package(request, package)\n';
    const path = join(directory, 'test_contract.py'); writeFileSync(path, code); files.contract = spec(path);
  }
  if (options.copyRequest) files.request = file(join(directory, 'mutable-request.json'), request);
  const manifest = {
    schema: 'lexicon.ielts.harness.manifest.v1', mode, python: PYTHON, runtimeRoot: RUNTIME_ROOT,
    files, receiptPath: join(state, 'receipt.json'),
    ...(options.candidateArtifact ? {candidatePath: join(state, 'validated-candidate.json')} : {}),
    ...(Object.hasOwn(options, 'candidateDelivery') ? {candidateDelivery: options.candidateDelivery} : {}),
    ...(options.continuation ? {continuationPath: join(inputs, 'continuation.json')} : {}),
    ...(mode !== 'generation' ? {reviewId: options.reviewId ?? (mode === 'review' ? review.review_id : explanationReview.review_id)} : {}),
    limits: {...options.limits},
  };
  const manifestFile = file(join(inputs, 'manifest.json'), manifest);
  return {directory, manifest, config: {manifestPath: manifestFile.path, manifestSha256: manifestFile.sha256}};
}
async function mount(f) {
  const ctx = new Context();
  const sessionFiber = ctx.plugin(SessionStore); await sessionFiber.await();
  ctx.provide('systemPrompt', {tools() {}, section() {}, getSectionOrder() { return 0; }});
  const toolsFiber = ctx.plugin(ToolRuntime, {mode: 'native', maxParallelSubCalls: 1}); await toolsFiber.await();
  const loaderFiber = ctx.plugin(Loader, {baseUrl: import.meta.url}); await loaderFiber.await();
  // Exercise the actual Loader's absolute module import and exported plugin API.
  await ctx.loader.create({id: 'lexicon-ielts', name: fileURLToPath(new URL('./harness_ielts_plugin.mjs', import.meta.url)), config: f.config});
  await ctx.loader.await();
  assert.equal(ctx.get(plugin.READY_SERVICE)?.ready, true, 'plugin must publish ready after registration');
  const loaded = ctx.loader.resolve('lexicon-ielts');
  assert.equal(loaded.moduleNamespace.name, plugin.name);
  const call = (name, args = {}, agent, signal = new AbortController().signal) => ctx.tools.execute({name, arguments: args, agent, signal, callId: `selftest-${caseNumber}`});
  const value = result => {
    assert.notEqual(result.isError, true, JSON.stringify(result.error ?? {}));
    return JSON.parse(result.content.find(item => item.type === 'text').text);
  };
  const cleanup = async () => { await ctx.loader.remove('lexicon-ielts'); await loaderFiber.dispose(); await toolsFiber.dispose(); await sessionFiber.dispose(); };
  return {ctx, call, value, cleanup, receipt: () => JSON.parse(readFileSync(f.manifest.receiptPath, 'utf8'))};
}
// Protocol fixtures use real Session.append and ToolRuntime execution. They
// do not assert native AgentLoop completion or persistence-to-disk success.
async function nativeMount(f = fixture('generation', {strictSchema: true, candidateArtifact: true, candidateDelivery: 'native_tool_conclusion'})) {
  const rt = await mount(f);
  const continuation = f.manifest.continuationPath && existsSync(f.manifest.continuationPath)
    ? JSON.parse(readFileSync(f.manifest.continuationPath, 'utf8')) : undefined;
  const session = rt.ctx.sessions.create(continuation?.sessionId ?? `session-native-selftest-${caseNumber}`);
  const agent = {session};
  const scope = createScope(rt.ctx.loader.resolve('lexicon-ielts').fiber.ctx, agent);
  let ordinal = 0;
  const invoke = async (name, args, options = {}) => {
    const callId = options.callId ?? `native-call-${++ordinal}`;
    const callEvent = session.append('tool/call', {turn: options.turn ?? 1, step: options.step ?? 1,
      callId, name, arguments: JSON.stringify(args)});
    const result = await rt.ctx.tools.execute({name, arguments: args, agent, callId,
      signal: options.signal ?? new AbortController().signal});
    const append = (mutate, targetSession = session) => {
      const data = {turn: callEvent.data.turn, step: callEvent.data.step,
        message: structuredClone(createToolResultMessage({callId, content: result.content, isError: result.isError})),
        ...(result.error?.info ? {error: result.error.info} : {}), ...(result.meta === undefined ? {} : {meta: result.meta})};
      const opts = {surfaceOp: 'append', sourceEventSeqs: [callEvent.seq]};
      mutate?.(data, opts);
      return targetSession.append('tool/result', data, opts);
    };
    return {callEvent, result, append};
  };
  return {...rt, f, session, agent, invoke, cleanup: async () => { await scope.dispose(); await rt.cleanup(); }};
}
async function test(name, fn) {
  const started = Date.now();
  try { await fn(); cases.push({name, passed: true, durationMs: Date.now() - started}); }
  catch (error) { cases.push({name, passed: false, diagnostic: error.message?.slice(0, 400)}); throw error; }
}
function stub(name, counter) {
  return {name, description: 'Offline forbidden test fixture', parameters: {type: 'object'}, output: {schema: {type: 'object'}, render: (_args, v) => [{type: 'text', text: JSON.stringify(v)}]}, execute() { counter.count++; return {forbidden: true}; }};
}

// Reads below execute through the real ToolRuntime. Native error/session
// metadata is synthetic: these fixtures are not a model recovery observation.
async function continuationFixture({mode = 'review', calls = 1, candidateDelivery, mutateFirst, mutateRuntime, mutateSeed} = {}) {
  const f = fixture(mode, {strictSchema: true, candidateArtifact: true, continuation: true,
    ...(candidateDelivery === undefined ? {} : {candidateDelivery}), limits: {maxToolCalls: 24}});
  const rt = candidateDelivery === 'native_tool_conclusion' ? await nativeMount(f) : await mount(f);
  try {
    for (let index = 0; index < calls; index++) {
      if (candidateDelivery === 'native_tool_conclusion') {
        const call = await rt.invoke('lexicon_ielts_read', {document_id: 'article:p1'}); rt.value(call.result); call.append();
      } else rt.value(await rt.call('lexicon_ielts_read', {document_id: 'article:p1'}));
    }
  } finally { await rt.cleanup(); }
  const state = dirname(f.manifest.receiptPath);
  const observedPath = join(state, 'observed-first-plugin-receipt.json');
  renameSync(f.manifest.receiptPath, observedPath);
  const first = JSON.parse(readFileSync(observedPath));
  mutateFirst?.(first);
  const attempt = join(state, 'attempt-01'); mkdirSync(attempt);
  const pluginReceipt = file(join(attempt, 'plugin-receipt.json'), first);
  const runtime = {
    schema: 'lexicon.ielts.runtime-receipt.v1', status: 'failed', stage: mode,
    runtime_version: '0.2.1-rc.5', session_id: candidateDelivery === 'native_tool_conclusion' ? rt.session.id : `session-plugin-selftest-${caseNumber}`,
    manifest_sha256: f.config.manifestSha256, plugin_sha256: spec(PLUGIN).sha256,
    corpus_sha256: f.manifest.files.corpus.sha256,
    schema_module_sha256: f.manifest.files.schemaModule.sha256, candidate_module_sha256: f.manifest.files.candidateModule.sha256,
    ...(mode === 'explanation_review' ? {
      explanation_review_module_sha256: f.manifest.files.explanationReviewModule.sha256,
      blind_review_input_sha256: f.manifest.files.blindReview.sha256,
    } : {}),
    required_tools: plugin.requiredStageTools(mode), native_attempt_count: 1,
    runtime_error_code: 'MALFORMED_RESPONSE', runtime_error_type: 'tool_input_invalid_json',
    exit_code: 1, terminal: 'error', turn_count: 1, terminal_count: 1, final_count: 1,
    errors: 0, malformed_frames: 0, process_reaped: true, stop_reason: null,
    input_write_failed: false, candidate_exists_after_attempt: false, tool_calls: first.allowedCount,
    tool_receipt: structuredClone(first),
  };
  mutateRuntime?.(runtime);
  const runtimeReceipt = file(join(attempt, 'runtime-receipt.json'), runtime);
  const seed = {
    schema: 'lexicon.ielts.continuation.v1', manifestSha256: f.config.manifestSha256,
    sessionId: runtime.session_id, runtimeReceiptSha256: runtimeReceipt.sha256,
    pluginReceiptSha256: pluginReceipt.sha256, carriedToolCalls: calls, maxToolCalls: 24,
  };
  mutateSeed?.(seed);
  const continuation = file(f.manifest.continuationPath, seed);
  return {f, first, runtime, seed, observedPath, pluginReceipt, runtimeReceipt, continuation};
}

try {
  await test('real loader registers exactly three owned tools and ready receipt', async () => {
    const f = fixture(); const rt = await mount(f);
    try {
      assert.deepEqual([...rt.ctx.tools.view().visible.keys()], plugin.TOOL_NAMES);
      const r = rt.receipt(); assert.equal(r.pluginReady, true); assert.equal(r.globalGuard, true); assert.equal(r.registrationIdentityVerified, true);
      assert.deepEqual(r.requiredTools, [...plugin.TOOL_NAMES]);
      assert.equal(r.allowedCount, 0);
    } finally { await rt.cleanup(); }
  });
  await test('frozen corpus retrieve and document read execute through actual ToolRuntime', async () => {
    const rt = await mount(fixture());
    try {
      const result = rt.value(await rt.call('lexicon_ielts_retrieve', {module: 'reading', question_type: 'multiple_choice', limit: 2}));
      assert.equal(result.schema, 'lexicon.ielts.corpus.search.v1'); assert(result.results.length > 0);
      const document = rt.value(await rt.call('lexicon_ielts_read', {document_id: result.results[0].document_id}));
      assert.equal(document.schema, 'lexicon.ielts.corpus.read.v1'); assert.equal(document.corpus_sha256, result.corpus_sha256);
      assert.equal(rt.receipt().completedCount, 2);
    } finally { await rt.cleanup(); }
  });
  await test('article paragraph inventory is appended without changing the 17 reference IDs', async () => {
    const rt = await mount(fixture());
    try {
      const schema = rt.ctx.tools.get('lexicon_ielts_read').parameters;
      assert.equal(schema.properties.document_id.enum.filter(id => id.startsWith('ielts.')).length, 17);
      assert.deepEqual(schema.properties.document_id.enum.filter(id => id.startsWith('article:')), ['article:p1', 'article:p2']);
      assert.equal(schema.additionalProperties, false);
      const result = rt.value(await rt.call('lexicon_ielts_read', {document_id: 'article:p2'}));
      assert.equal(result.schema, 'lexicon.ielts.article.read.v1');
      assert.deepEqual({paragraph_id: result.paragraph_id, start: result.start, end: result.end, text: result.text}, request.paragraphs[1]);
    } finally { await rt.cleanup(); }
  });
  await test('duplicate quotations return all exact UTF-16 ranges after an emoji', async () => {
    const rt = await mount(fixture());
    try {
      const quote = 'The council approved solar panels.';
      const result = rt.value(await rt.call('lexicon_ielts_read', {document_id: 'article:p1', quote}));
      assert.equal(result.schema, 'lexicon.ielts.article.matches.v1'); assert.equal(result.match_count, 2);
      const paragraph = request.paragraphs[0];
      assert.deepEqual(result.ranges, [paragraph.text.indexOf(quote), paragraph.text.lastIndexOf(quote)].map(index => ({paragraph_id: 'p1', start: paragraph.start + index, end: paragraph.start + index + quote.length, quote})));
      assert.equal(result.ranges[0].start, 3, 'emoji consumes two UTF-16 code units');
      const updated = structuredClone(candidate); updated.reading_questions[0].source_refs = [result.ranges[1]];
      assert.equal(rt.value(await rt.call('lexicon_ielts_validate', {candidate: updated})).status, 'structurally_valid');
    } finally { await rt.cleanup(); }
  });
  await test('emoji and later paragraph matches preserve absolute UTF-16 boundaries', async () => {
    const fixedRequest = requestWithParagraphs(['First 🔬 paragraph.', 'Now 😀 proof follows 😀.']);
    const rt = await mount(fixture('generation', {request: fixedRequest}));
    try {
      const result = rt.value(await rt.call('lexicon_ielts_read', {document_id: 'article:p2', quote: '😀'}));
      const paragraph = fixedRequest.paragraphs[1];
      assert.deepEqual(result.ranges.map(range => [range.start, range.end]), [paragraph.text.indexOf('😀'), paragraph.text.lastIndexOf('😀')].map(index => [paragraph.start + index, paragraph.start + index + 2]));
      assert(result.ranges.every(range => paragraph.text.slice(range.start - paragraph.start, range.end - paragraph.start) === '😀'));
    } finally { await rt.cleanup(); }
  });
  await test('literal search does not interpret regex syntax or normalise text', async () => {
    const fixedRequest = requestWithParagraphs(['Literal a.b and aXb, then aaa; Café is distinct from Cafe.']);
    const rt = await mount(fixture('generation', {request: fixedRequest}));
    try {
      const literal = rt.value(await rt.call('lexicon_ielts_read', {document_id: 'article:p1', quote: 'a.b'}));
      assert.equal(literal.match_count, 1); assert.equal(literal.ranges[0].quote, 'a.b');
      const overlap = rt.value(await rt.call('lexicon_ielts_read', {document_id: 'article:p1', quote: 'aa'}));
      assert.equal(overlap.match_count, 2); assert.equal(overlap.ranges[1].start, overlap.ranges[0].start + 1);
      for (const quote of ['a.*b', 'CAFE', 'Cafe\u0301']) {
        const absent = rt.value(await rt.call('lexicon_ielts_read', {document_id: 'article:p1', quote}));
        assert.equal(absent.ok, false); assert.equal(absent.error.code, 'quote_not_found');
      }
    } finally { await rt.cleanup(); }
  });
  await test('eight matches are complete and a ninth rejects rather than truncates', async () => {
    const fixedRequest = requestWithParagraphs([Array(8).fill('eight').join(' '), Array(9).fill('nine').join(' ')]);
    const rt = await mount(fixture('generation', {request: fixedRequest}));
    try {
      const complete = rt.value(await rt.call('lexicon_ielts_read', {document_id: 'article:p1', quote: 'eight'}));
      assert.equal(complete.match_count, 8); assert.equal(complete.ranges.length, 8);
      const excess = rt.value(await rt.call('lexicon_ielts_read', {document_id: 'article:p2', quote: 'nine'}));
      assert.equal(excess.ok, false); assert.equal(excess.error.code, 'quote_match_bound'); assert(!Object.hasOwn(excess, 'ranges'));
    } finally { await rt.cleanup(); }
  });
  await test('quote bounds use UTF-16 units and reject lookup targets or reference-corpus quotes', async () => {
    const fixedRequest = requestWithParagraphs(['😀'.repeat(1000)]);
    const rt = await mount(fixture('generation', {request: fixedRequest}));
    try {
      const maximum = rt.value(await rt.call('lexicon_ielts_read', {document_id: 'article:p1', quote: '😀'.repeat(1000)}));
      assert.equal(maximum.match_count, 1); assert.equal(maximum.ranges[0].end, 2000);
      for (const quote of ['', '😀'.repeat(1001)]) {
        const invalid = await rt.call('lexicon_ielts_read', {document_id: 'article:p1', quote});
        assert.equal(invalid.isError, true); assert.match(invalid.content[0].text, /quote_bound/);
      }
      for (const quote of ['https://example.com', '/etc/passwd', '../article.txt']) {
        const invalid = rt.value(await rt.call('lexicon_ielts_read', {document_id: 'article:p1', quote}));
        assert.equal(invalid.error.code, 'quote_lookup_target');
      }
      const corpusQuote = await rt.call('lexicon_ielts_read', {document_id: 'ielts.reading.multiple_choice', quote: 'answer'});
      assert.equal(corpusQuote.isError, true); assert.match(corpusQuote.content[0].text, /quote_article_only/);
    } finally { await rt.cleanup(); }
  });
  await test('article reads use only the pinned request and cannot accept supplied paragraph text', async () => {
    const f = fixture(); const rt = await mount(f);
    try {
      writeFileSync(join(f.directory, 'p1.txt'), 'UNBOUND_EXTERNAL_PARAGRAPH');
      const paragraph = rt.value(await rt.call('lexicon_ielts_read', {document_id: 'article:p1'}));
      assert.equal(paragraph.text, request.paragraphs[0].text);
      const absent = rt.value(await rt.call('lexicon_ielts_read', {document_id: 'article:p1', quote: 'UNBOUND_EXTERNAL_PARAGRAPH'}));
      assert.equal(absent.error.code, 'quote_not_found');
      for (const args of [{document_id: 'article:missing'}, {document_id: 'article:../p1'}, {document_id: 'article:p1', text: 'Replacement'}, {document_id: 'article:p1', path: join(f.directory, 'p1.txt')}]) assert.equal((await rt.call('lexicon_ielts_read', args)).isError, true);
      writeFileSync(f.manifest.files.request.path, JSON.stringify({...request, paragraphs: []}));
      const changed = await rt.call('lexicon_ielts_read', {document_id: 'article:p1'});
      assert.equal(changed.isError, true); assert.match(changed.content[0].text, /input_digest/);
    } finally { await rt.cleanup(); }
  });
  await test('review article retrieval exposes original paragraphs but no generator answers', async () => {
    const generation = await mount(fixture()); const independentReview = await mount(fixture('review'));
    try {
      const args = {document_id: 'article:p1', quote: 'solar panels'};
      const source = generation.value(await generation.call('lexicon_ielts_read', args));
      const reviewed = independentReview.value(await independentReview.call('lexicon_ielts_read', args));
      assert.deepEqual(reviewed, source);
      const absent = independentReview.value(await independentReview.call('lexicon_ielts_read', {document_id: 'article:p1', quote: 'SECRET_RATIONALE_Q1'}));
      assert.equal(absent.error.code, 'quote_not_found');
      const full = independentReview.value(await independentReview.call('lexicon_ielts_read', {document_id: 'article:p1'}));
      assert.equal(full.text, request.paragraphs[0].text);
      for (const hidden of ['SECRET_RATIONALE', 'explanation', 'answer_agreement', 'blockers']) assert(!JSON.stringify(full).includes(hidden));
    } finally { await generation.cleanup(); await independentReview.cleanup(); }
  });
  await test('global default-deny blocks shell file network subagent and run_code bodies', async () => {
    const rt = await mount(fixture()); const counter = {count: 0};
    try {
      for (const name of ['bash', 'read_file', 'web_search', 'subagent']) rt.ctx.tools.register(stub(name, counter));
      for (const name of ['bash', 'read_file', 'web_search', 'subagent', 'run_code']) {
        const result = await rt.call(name); assert.equal(result.isError, true); assert.match(result.content[0].text, /tool_denied/);
      }
      assert.equal(counter.count, 0); assert.equal(rt.receipt().deniedCount, 5);
    } finally { await rt.cleanup(); }
  });
  await test('agent-local same-name shadow cannot bypass registration-object identity guard', async () => {
    const rt = await mount(fixture()); const counter = {count: 0}; const agent = {};
    const scope = createScope(rt.ctx.loader.resolve('lexicon-ielts').fiber.ctx, agent);
    try {
      scope.ctx.tools.register(stub('lexicon_ielts_read', counter));
      const result = await rt.call('lexicon_ielts_read', {document_id: 'ielts.reading.multiple_choice'}, agent);
      assert.equal(result.isError, true); assert.equal(counter.count, 0); assert(rt.receipt().identityFailures >= 1);
    } finally { await scope.dispose(); await rt.cleanup(); }
  });
  await test('global same-name registration is rejected by real runtime', async () => {
    const rt = await mount(fixture());
    try { assert.throws(() => rt.ctx.tools.register(stub('lexicon_ielts_read', {count: 0})), /already|duplicate|registered/i); }
    finally { await rt.cleanup(); }
  });
  await test('valid generation candidate checks exact frozen request and ranges', async () => {
    const rt = await mount(fixture());
    try {
      const result = rt.value(await rt.call('lexicon_ielts_validate', {candidate}));
      assert.equal(result.status, 'structurally_valid'); assert.equal(result.semantic_review, 'required');
      const invalid = structuredClone(candidate); invalid.reading_questions[0].source_refs[0].start++;
      const rejected = rt.value(await rt.call('lexicon_ielts_validate', {candidate: invalid}));
      assert.equal(rejected.ok, false); assert(rejected.error.code); assert(!('message' in rejected.error));
    } finally { await rt.cleanup(); }
  });
  await test('review correct and disagreeing answers return identical non-oracle structure receipt', async () => {
    const rt = await mount(fixture('review'));
    try {
      const positive = rt.value(await rt.call('lexicon_ielts_validate', {candidate: review}));
      const disagreeing = structuredClone(review); disagreeing.reading_reviews[0].observed_answer = 'B';
      const negative = rt.value(await rt.call('lexicon_ielts_validate', {candidate: disagreeing}));
      assert.deepEqual(positive, {structurally_valid: true}); assert.deepEqual(negative, positive);
      for (const secret of ['blockers', 'answer_agreement', 'reading_results', 'status', 'SECRET_RATIONALE']) assert(!JSON.stringify(negative).includes(secret));
    } finally { await rt.cleanup(); }
  });
  await test('strict validated candidate is atomically stored and confirmed by its actual byte digest', async () => {
    const candidate = generationInput();
    const f = fixture('generation', {strictSchema: true, candidateArtifact: true}); const rt = await mount(f);
    try {
      assert(!existsSync(f.manifest.candidatePath));
      const result = rt.value(await rt.call('lexicon_ielts_validate', {candidate}));
      const bytes = readFileSync(f.manifest.candidatePath);
      assert.deepEqual(JSON.parse(bytes), compiledGeneration(candidate));
      assert.equal(result.candidate_sha256, sha(bytes));
      assert.equal(result.package_sha256, result.candidate_sha256, 'canonical JS and frozen Python digests agree');
      assert.equal(rt.receipt().candidateSha256, result.candidate_sha256);
      assert.equal(rt.receipt().candidateBytes, bytes.length);
      assert.equal(rt.receipt().completedPerTool.lexicon_ielts_validate, 1);
      assert.equal(statSync(f.manifest.candidatePath).mode & 0o777, 0o600);
      assert.equal(Object.hasOwn(result, 'candidate'), false, 'the tool does not echo the large candidate');
      const updated = structuredClone(candidate); updated.reading_questions[0].explanation += ' 本机补充说明。';
      const second = rt.value(await rt.call('lexicon_ielts_validate', {candidate: updated}));
      assert.notEqual(second.candidate_sha256, result.candidate_sha256);
      assert.deepEqual(JSON.parse(readFileSync(f.manifest.candidatePath)), compiledGeneration(updated));
      assert.equal(rt.receipt().candidateSha256, second.candidate_sha256);
    } finally { await rt.cleanup(); }
  });
  await test('a later structurally invalid candidate cannot confirm the prior stored digest', async () => {
    const candidate = generationInput();
    const f = fixture('generation', {strictSchema: true, candidateArtifact: true}); const rt = await mount(f);
    try {
      rt.value(await rt.call('lexicon_ielts_validate', {candidate}));
      const invalid = structuredClone(candidate); invalid.reading_questions[0].source_refs[0].start++;
      const result = rt.value(await rt.call('lexicon_ielts_validate', {candidate: invalid}));
      assert.equal(result.ok, false);
      assert.equal(rt.receipt().candidateSha256, null); assert.equal(rt.receipt().candidateBytes, 0);
      assert.equal(rt.receipt().completedPerTool.lexicon_ielts_validate, 1);
    } finally { await rt.cleanup(); }
  });
  await test('locators compile to a canonical artifact and only its digest is returned', async () => {
    const f = fixture('generation', {strictSchema: true, candidateArtifact: true}); const rt = await mount(f);
    try {
      const {raw, compiled} = compilerFixture();
      const before = JSON.stringify(raw);
      const result = rt.value(await rt.call('lexicon_ielts_validate', {candidate: raw}));
      const bytes = readFileSync(f.manifest.candidatePath);
      assert.deepEqual(JSON.parse(bytes), compiled);
      assert.equal(JSON.stringify(raw), before);
      assert.equal(result.candidate_sha256, sha(bytes));
      assert.equal(result.package_sha256, sha(bytes));
      assert.equal(rt.receipt().candidateCompilerReady, true);
      assert.equal(rt.receipt().candidateModuleSha256, f.manifest.files.candidateModule.sha256);
      assert(!Object.hasOwn(result, 'artifact')); assert(!Object.hasOwn(result, 'candidate'));
      assert.equal(statSync(f.manifest.candidatePath).mode & 0o777, 0o600);
    } finally { await rt.cleanup(); }
  });
  await test('locator ambiguity returns a fixed read-tool hint and invalidates the prior digest', async () => {
    const candidate = generationInput();
    const f = fixture('generation', {strictSchema: true, candidateArtifact: true}); const rt = await mount(f);
    try {
      rt.value(await rt.call('lexicon_ielts_validate', {candidate}));
      const raw = structuredClone(candidate);
      raw.reading_questions[0].source_refs = raw.reading_questions[0].source_refs.map(locator);
      const rejected = rt.value(await rt.call('lexicon_ielts_validate', {candidate: raw}));
      assert.equal(rejected.ok, false); assert.equal(rejected.error.code, 'candidate_quote_ambiguous');
      assert.match(rejected.error.hint, /lexicon_ielts_read/);
      assert(!JSON.stringify(rejected).includes('solar panels'));
      assert.equal(rt.receipt().candidateSha256, null);
    } finally { await rt.cleanup(); }
  });
  await test('compiled artifact retains the input byte cap after adding ranges', async () => {
    const {raw} = compilerFixture();
    const limit = Buffer.byteLength(JSON.stringify({candidate: raw})) + 1;
    const f = fixture('generation', {strictSchema: true, candidateArtifact: true, limits: {maxInputBytes: limit}});
    const rt = await mount(f);
    try {
      const rejected = rt.value(await rt.call('lexicon_ielts_validate', {candidate: raw}));
      assert.equal(rejected.ok, false); assert.equal(rejected.error.code, 'candidate_bound');
      assert(!existsSync(f.manifest.candidatePath)); assert.equal(rt.receipt().candidateSha256, null);
    } finally { await rt.cleanup(); }
  });
  await test('review locator compilation keeps checks opaque and preserves option reference indices', async () => {
    const f = fixture('review', {strictSchema: true, candidateArtifact: true}); const rt = await mount(f);
    try {
      const {raw, compiled} = compilerFixture('review');
      raw.reading_reviews[0].observed_answer = 'B'; compiled.reading_reviews[0].observed_answer = 'B';
      const result = rt.value(await rt.call('lexicon_ielts_validate', {candidate: raw}));
      assert.deepEqual(Object.keys(result).sort(), ['candidate_sha256', 'structurally_valid']);
      assert.deepEqual(JSON.parse(readFileSync(f.manifest.candidatePath)), compiled);
      assert.deepEqual(compiled.reading_reviews[0].option_reviews, review.reading_reviews[0].option_reviews);
    } finally { await rt.cleanup(); }
  });
  await test('writing instruction ambiguity remains a complete opaque review despite old pass checks', async () => {
    const f = fixture('review', {strictSchema: true, candidateArtifact: true}); const rt = await mount(f);
    try {
      const ambiguous = structuredClone(review);
      const alignment = ambiguous.writing_task2_review.instruction_alignment;
      const second = {...alignment.target_readings[0], interpretation: 'Whether other public spending should cease altogether.'};
      Object.assign(alignment, {relation: 'ambiguous', target_readings: [...alignment.target_readings, second],
        reason: 'Synthetic instruction ambiguity fixture; no actual semantic assessment.'});
      assert(Object.values(ambiguous.writing_task2_review.checks).every(check => check.status === 'pass'));
      const result = rt.value(await rt.call('lexicon_ielts_validate', {candidate: ambiguous}));
      assert.deepEqual(Object.keys(result).sort(), ['candidate_sha256', 'structurally_valid']);
      assert.deepEqual(JSON.parse(readFileSync(f.manifest.candidatePath)), ambiguous);
    } finally { await rt.cleanup(); }
  });
  await test('writing alignment rejects article quotations and missing targets without exposing answer agreement', async () => {
    const f = fixture('review', {strictSchema: true, candidateArtifact: true}); const rt = await mount(f);
    try {
      const missing = structuredClone(review); missing.writing_task2_review.instruction_alignment.target_readings = [];
      const quote = structuredClone(review);
      quote.writing_task2_review.instruction_alignment.instruction_quote = request.paragraphs[0].text;
      for (const malformed of [missing, quote]) {
        const result = rt.value(await rt.call('lexicon_ielts_validate', {candidate: malformed}));
        assert.equal(result.ok, false);
        assert(!Object.hasOwn(result, 'blockers')); assert(!Object.hasOwn(result, 'answer_agreement'));
        assert.equal(rt.receipt().candidateSha256, null);
      }
      assert.equal(existsSync(f.manifest.candidatePath), false);
    } finally { await rt.cleanup(); }
  });
  await test('post-blind explanation review compiles exact ranges into its own bound artifact', async () => {
    const f = fixture('explanation_review', {strictSchema: true, candidateArtifact: true}); const rt = await mount(f);
    try {
      const {raw, compiled} = compilerFixture('explanation_review');
      const result = rt.value(await rt.call('lexicon_ielts_validate', {candidate: raw}));
      const bytes = readFileSync(f.manifest.candidatePath);
      assert.deepEqual(JSON.parse(bytes), compiled);
      assert.deepEqual(result, {structurally_valid: true, candidate_sha256: sha(bytes)});
      const receipt = rt.receipt();
      assert.equal(receipt.mode, 'explanation_review');
      assert.equal(receipt.explanationReviewModuleSha256, f.manifest.files.explanationReviewModule.sha256);
      assert.equal(receipt.blindReviewInputSha256, f.manifest.files.blindReview.sha256);
      assert.equal(receipt.candidateCompilerReady, true);
      assert.equal(receipt.candidateSchemaStrict, true);
      assert.equal(receipt.completedPerTool.lexicon_ielts_validate, 1);
      assert.deepEqual(receipt.requiredTools, ['lexicon_ielts_validate']);
      assert.equal(receipt.completedPerTool.lexicon_ielts_retrieve, 0);
      assert.equal(receipt.completedPerTool.lexicon_ielts_read, 0);
    } finally { await rt.cleanup(); }
  });
  await test('review policy is fixed while optional source tools stay registered and counts stay real', async () => {
    assert.deepEqual(plugin.requiredStageTools('generation'), [...plugin.TOOL_NAMES]);
    for (const mode of ['review', 'explanation_review']) {
      assert.deepEqual(plugin.requiredStageTools(mode), ['lexicon_ielts_validate']);
      const f = fixture(mode, {strictSchema: true, candidateArtifact: true}); const rt = await mount(f);
      try {
        assert.deepEqual([...rt.ctx.tools.view().visible.keys()], plugin.TOOL_NAMES);
        rt.value(await rt.call('lexicon_ielts_validate', {candidate: mode === 'review' ? review : explanationReview}));
        assert.deepEqual(rt.receipt().requiredTools, ['lexicon_ielts_validate']);
        assert.deepEqual(rt.receipt().completedPerTool,
          {lexicon_ielts_retrieve: 0, lexicon_ielts_read: 0, lexicon_ielts_validate: 1});
        const source = rt.value(await rt.call('lexicon_ielts_read', {document_id: 'article:p2'}));
        assert.equal(source.text, request.paragraphs[1].text);
        assert.deepEqual(rt.receipt().completedPerTool,
          {lexicon_ielts_retrieve: 0, lexicon_ielts_read: 1, lexicon_ielts_validate: 1});
      } finally { await rt.cleanup(); }
    }
    assert.throws(() => plugin.requiredStageTools('model-chosen'), /invalid_mode/);
  });
  await test('an honest explanation rejection is saved without rewriting the package or blind answers', async () => {
    const f = fixture('explanation_review', {strictSchema: true, candidateArtifact: true}); const rt = await mount(f);
    try {
      const rejected = structuredClone(explanationReview);
      rejected.explanation_reviews[0].unit_reviews[0].relation = 'unsupported';
      rejected.explanation_reviews[0].unit_reviews[0].reason = 'Synthetic finding: the explanation changes the population quantifier.';
      const result = rt.value(await rt.call('lexicon_ielts_validate', {candidate: rejected}));
      assert.equal(result.structurally_valid, true);
      assert.deepEqual(Object.keys(result).sort(), ['candidate_sha256', 'structurally_valid']);
      assert.deepEqual(JSON.parse(readFileSync(f.manifest.candidatePath)), rejected);
      assert.deepEqual(JSON.parse(readFileSync(f.manifest.files.package.path)), candidate);
      assert.deepEqual(JSON.parse(readFileSync(f.manifest.files.blindReview.path)), review);
    } finally { await rt.cleanup(); }
  });
  await test('explanation review cannot change frozen package or blind identity in a candidate', async () => {
    const f = fixture('explanation_review', {strictSchema: true, candidateArtifact: true}); const rt = await mount(f);
    try {
      for (const field of ['package_sha256', 'blind_review_sha256']) {
        const stale = structuredClone(explanationReview); stale[field] = 'a'.repeat(64);
        const result = await rt.call('lexicon_ielts_validate', {candidate: stale});
        assert.equal(result.isError, true); assert.equal(rt.receipt().candidateSha256, null);
      }
      const extra = structuredClone(explanationReview); extra.explanation_reviews[0].replacement_explanation = 'Model rewrite';
      assert.equal((await rt.call('lexicon_ielts_validate', {candidate: extra})).isError, true);
      assert.equal(existsSync(f.manifest.candidatePath), false);
    } finally { await rt.cleanup(); }
  });
  await test('explanation units must cover the frozen table and cannot assert a replacement overall pass', async () => {
    const f = fixture('explanation_review', {strictSchema: true, candidateArtifact: true}); const rt = await mount(f);
    try {
      const mutations = [
        value => value.explanation_reviews[0].unit_reviews.pop(),
        value => { value.explanation_reviews[0].unit_reviews[0].unit_id = 'unrelated'; },
        value => { value.explanation_reviews[0].unit_reviews[0].source_ref_indices = [1]; },
        value => { value.explanation_reviews[0].status = 'pass'; },
      ];
      for (const mutate of mutations) {
        const malformed = structuredClone(explanationReview); mutate(malformed);
        const result = await rt.call('lexicon_ielts_validate', {candidate: malformed});
        // Shape errors are runtime failures; count/range checks belong to the
        // Python validator and return its bounded structured rejection.
        if (result.isError !== true) assert.equal(rt.value(result).ok, false);
        assert.equal(rt.receipt().candidateSha256, null);
      }
      assert.equal(existsSync(f.manifest.candidatePath), false);
    } finally { await rt.cleanup(); }
  });
  await test('ambiguous unit readings remain explicit in a completed explanation artifact', async () => {
    const f = fixture('explanation_review', {strictSchema: true, candidateArtifact: true}); const rt = await mount(f);
    try {
      const ambiguous = structuredClone(explanationReview);
      Object.assign(ambiguous.explanation_reviews[0].unit_reviews[0], {
        scope: 'ambiguous', relation: 'ambiguous',
        interpretations: ['The passage does not report a measurement.', 'No measurement took place.'],
        reason: 'Synthetic ambiguity fixture; no semantic assessment was performed.',
      });
      const result = rt.value(await rt.call('lexicon_ielts_validate', {candidate: ambiguous}));
      assert.deepEqual(Object.keys(result).sort(), ['candidate_sha256', 'structurally_valid']);
      assert.deepEqual(JSON.parse(readFileSync(f.manifest.candidatePath)), ambiguous);
      assert.deepEqual(JSON.parse(readFileSync(f.manifest.files.package.path)), candidate);
    } finally { await rt.cleanup(); }
  });
  await test('explanation module and blind input bytes stay pinned across calls', async () => {
    for (const key of ['explanationReviewModule', 'blindReview']) {
      const f = fixture('explanation_review', {strictSchema: true, candidateArtifact: true, copyExplanation: true}); const rt = await mount(f);
      try {
        writeFileSync(f.manifest.files[key].path, readFileSync(f.manifest.files[key].path, 'utf8') + '\n');
        const result = await rt.call('lexicon_ielts_validate', {candidate: explanationReview});
        assert.equal(result.isError, true);
        assert.equal(rt.receipt().candidateSha256, null);
      } finally { await rt.cleanup(); }
    }
  });
  await test('explanation stage refuses a disagreeing blind review before registering ready', async () => {
    const disagrees = structuredClone(review); disagrees.reading_reviews[0].observed_answer = 'B';
    const f = fixture('explanation_review', {strictSchema: true, candidateArtifact: true, blindReview: disagrees});
    await assert.rejects(mount(f));
    assert.equal(JSON.parse(readFileSync(f.manifest.receiptPath)).pluginReady, false);
    assert.equal(existsSync(f.manifest.candidatePath), false);
  });
  await test('compiler module is pinned and cannot be chosen through model arguments', async () => {
    const candidate = generationInput();
    const f = fixture('generation', {strictSchema: true, candidateArtifact: true, copyCompiler: true}); const rt = await mount(f);
    try {
      const injected = await rt.call('lexicon_ielts_validate', {candidate, candidateModule: '/tmp/other.py'});
      assert.equal(injected.isError, true);
      writeFileSync(f.manifest.files.candidateModule.path, readFileSync(f.manifest.files.candidateModule.path, 'utf8') + '\n# replaced\n');
      const rejected = await rt.call('lexicon_ielts_validate', {candidate});
      assert.equal(rejected.isError, true); assert.equal(rt.receipt().candidateSha256, null);
      assert(!existsSync(f.manifest.candidatePath));
    } finally { await rt.cleanup(); }
  });
  await test('oversized or malformed later validation invalidates the prior digest before input checks', async () => {
    const candidate = generationInput();
    const f = fixture('generation', {strictSchema: true, candidateArtifact: true, limits: {maxInputBytes: 6000}}); const rt = await mount(f);
    try {
      const oversized = structuredClone(candidate); oversized.reading_questions[0].explanation = 'x'.repeat(7000);
      for (const args of [{candidate: oversized}, {}, {candidate: {}}]) {
        rt.value(await rt.call('lexicon_ielts_validate', {candidate}));
        assert(rt.receipt().candidateSha256);
        const failed = await rt.call('lexicon_ielts_validate', args);
        assert.equal(failed.isError, true);
        assert.equal(rt.receipt().candidateSha256, null);
        assert.equal(rt.receipt().candidateBytes, 0);
      }
    } finally { await rt.cleanup(); }
  });
  await test('review artifacts contain the complete independent review without exposing the agreement gate', async () => {
    const f = fixture('review', {strictSchema: true, candidateArtifact: true}); const rt = await mount(f);
    try {
      const accepted = rt.value(await rt.call('lexicon_ielts_validate', {candidate: review}));
      assert.deepEqual(Object.keys(accepted).sort(), ['candidate_sha256', 'structurally_valid']);
      const disagreeing = structuredClone(review); disagreeing.reading_reviews[0].observed_answer = 'B';
      const rejectedSemantics = rt.value(await rt.call('lexicon_ielts_validate', {candidate: disagreeing}));
      assert.equal(rejectedSemantics.structurally_valid, true);
      assert.deepEqual(Object.keys(rejectedSemantics).sort(), Object.keys(accepted).sort());
      assert.deepEqual(JSON.parse(readFileSync(f.manifest.candidatePath)), disagreeing);
      assert.equal(rejectedSemantics.candidate_sha256, sha(readFileSync(f.manifest.candidatePath)));
      for (const hidden of ['blockers', 'answer_agreement', 'status', 'reading_results']) assert(!Object.hasOwn(rejectedSemantics, hidden));
    } finally { await rt.cleanup(); }
  });
  await test('candidate artifacts require a pinned schema and cannot overwrite a prior artifact', async () => {
    const schemaMissing = fixture('generation', {candidateArtifact: true});
    await assert.rejects(() => plugin.apply({}, schemaMissing.config), /candidate_schema_required/);
    const compilerMissing = fixture('generation', {strictSchema: true, candidateArtifact: true});
    delete compilerMissing.manifest.files.candidateModule;
    compilerMissing.config.manifestSha256 = file(compilerMissing.config.manifestPath, compilerMissing.manifest).sha256;
    await assert.rejects(() => plugin.apply({}, compilerMissing.config), /candidate_compiler_required/);
    const occupied = fixture('generation', {strictSchema: true, candidateArtifact: true});
    writeFileSync(occupied.manifest.candidatePath, 'prior owned artifact');
    await assert.rejects(() => plugin.apply({}, occupied.config), /candidate_already_exists/);
    assert.equal(readFileSync(occupied.manifest.candidatePath, 'utf8'), 'prior owned artifact');
    const collision = fixture('generation', {strictSchema: true, candidateArtifact: true});
    collision.manifest.candidatePath = collision.manifest.receiptPath;
    const manifestFile = file(collision.config.manifestPath, collision.manifest);
    collision.config.manifestSha256 = manifestFile.sha256;
    await assert.rejects(() => plugin.apply({}, collision.config), /candidate_output_collision/);
  });
  await test('pinned generation schema is registered and accepts the full valid candidate', async () => {
    const candidate = generationInput();
    const f = fixture('generation', {strictSchema: true}); const rt = await mount(f);
    try {
      const declared = rt.ctx.tools.get('lexicon_ielts_validate').parameters.properties.candidate;
      assert.equal(declared.type, 'object'); assert.equal(declared.additionalProperties, false);
      assert(declared.required.includes('reading_questions')); assert(declared.required.includes('writing_task2'));
      const writing = declared.properties.writing_task2;
      assert(writing.required.includes('prompt_draft')); assert(!Object.hasOwn(writing.properties, 'prompt'));
      assert.deepEqual(writing.properties.prompt_draft.required, ['background', 'target_statement']);
      assert.equal(writing.properties.prompt_draft.additionalProperties, false);
      assert.equal(rt.receipt().candidateSchemaStrict, true);
      assert.equal(rt.receipt().schemaModuleSha256, f.manifest.files.schemaModule.sha256);
      assert.equal(rt.ctx.get(plugin.READY_SERVICE).strictSchemaReady, true);
      assert.equal(rt.value(await rt.call('lexicon_ielts_validate', {candidate})).status, 'structurally_valid');
    } finally { await rt.cleanup(); }
  });
  await test('native writing draft compiles exact text into a prompt-only canonical artifact', async () => {
    for (const draft of [SYNTHETIC_WRITING_DRAFT,
      {background: '', target_statement: 'Public investment should balance affordability and access'},
      {background: '', target_statement: '  Public investment should prioritise access  '},
    ]) {
      const f = fixture('generation', {strictSchema: true, candidateArtifact: true}); const rt = await mount(f);
      try {
        const raw = generationInput(candidate, draft); const before = JSON.stringify(raw);
        const result = rt.value(await rt.call('lexicon_ielts_validate', {candidate: raw}));
        const bytes = readFileSync(f.manifest.candidatePath); const compiled = JSON.parse(bytes);
        assert.deepEqual(compiled, compiledGeneration(raw)); assert.equal(JSON.stringify(raw), before);
        assert(compiled.writing_task2.prompt.endsWith(WRITING_INSTRUCTION));
        assert.deepEqual(Object.keys(compiled.writing_task2).sort(), ['task_id', 'task_type', 'prompt', 'minimum_words', 'suggested_minutes', 'source_refs'].sort());
        assert.equal(result.candidate_sha256, sha(bytes)); assert.equal(rt.receipt().candidateSha256, sha(bytes));
        assert.equal(rt.receipt().completedPerTool.lexicon_ielts_validate, 1);
      } finally { await rt.cleanup(); }
    }
  });
  await test('unquoted writing background is rejected without saving a candidate', async () => {
    const f = fixture('generation', {strictSchema: true, candidateArtifact: true}); const rt = await mount(f);
    try {
      const raw = generationInput(candidate, {background: 'Public budgets support several competing priorities.',
        target_statement: SYNTHETIC_WRITING_DRAFT.target_statement});
      const result = rt.value(await rt.call('lexicon_ielts_validate', {candidate: raw}));
      assert.equal(result.ok, false); assert.equal(result.error.code, 'candidate_writing_background_not_extractive');
      assert(!existsSync(f.manifest.candidatePath)); assert.equal(rt.receipt().candidateSha256, null);
    } finally { await rt.cleanup(); }
  });
  await test('native schema rejects legacy prompts mixed prompt fields and open-ended draft keys without an artifact', async () => {
    const both = generationInput(); both.writing_task2.prompt = candidate.writing_task2.prompt;
    const missing = generationInput(); delete missing.writing_task2.prompt_draft.background;
    const extra = generationInput(); extra.writing_task2.prompt_draft.target = 'Unbound duplicate target';
    for (const raw of [candidate, both, missing, extra]) {
      const f = fixture('generation', {strictSchema: true, candidateArtifact: true}); const rt = await mount(f);
      try {
        const result = await rt.call('lexicon_ielts_validate', {candidate: raw});
        assert.equal(result.isError, true); assert.match(result.content[0].text, /candidate_schema/);
        assert(!existsSync(f.manifest.candidatePath)); assert.equal(rt.receipt().candidateSha256, null);
        assert.equal(rt.receipt().candidateBytes, 0); assert.equal(rt.receipt().completedPerTool.lexicon_ielts_validate, 0);
      } finally { await rt.cleanup(); }
    }
  });
  await test('native tool rejects structurally typed empty multiline and oversized writing drafts without an artifact', async () => {
    for (const draft of [
      {background: '', target_statement: ''}, {background: ' ', target_statement: 'A substantive claim.'},
      {background: '', target_statement: 'One claim.\nAnother claim.'},
      {background: '', target_statement: 'A URL at https://example.com is not a writing target.'},
      {background: 'x'.repeat(900), target_statement: 'y'.repeat(900)},
    ]) {
      const f = fixture('generation', {strictSchema: true, candidateArtifact: true}); const rt = await mount(f);
      try {
        const result = rt.value(await rt.call('lexicon_ielts_validate', {candidate: generationInput(candidate, draft)}));
        assert.equal(result.ok, false); assert(result.error.code);
        assert(!existsSync(f.manifest.candidatePath)); assert.equal(rt.receipt().candidateSha256, null);
        assert.equal(rt.receipt().candidateBytes, 0); assert.equal(rt.receipt().completedPerTool.lexicon_ielts_validate, 0);
      } finally { await rt.cleanup(); }
    }
  });
  await test('large legal startup schemas use a separate host cap while tool results remain bounded', async () => {
    const prepared = spawnSync(PYTHON, ['-I', '-B', '-c', `
import json,sys
sys.path.insert(0,sys.argv[1])
from tools.test_ielts_json_schema import capacity_fixture,ready_bytes
from tools.ielts_json_schema import generation_schema,review_schema
fixtures=[]
for mixed in (False,True):
 request,package,review_id=capacity_fixture(mixed_rules=mixed,long_ids=True)
 sizes=[ready_bytes(request,generation_schema(request)),ready_bytes(request,review_schema(request,package,review_id))]
 fixtures.append([request,package,review_id,sizes])
print(json.dumps(fixtures,ensure_ascii=False))
`, ROOT], {env: cleanEnv, encoding: 'utf8', maxBuffer: 1048576});
    assert.equal(prepared.status, 0, 'maximum schema fixtures must load');
    let largeSchemas = 0;
    for (const [request, candidate, reviewId, sizes] of JSON.parse(prepared.stdout)) {
      for (const mode of ['generation', 'review']) {
        const size = sizes[mode === 'generation' ? 0 : 1];
        assert(size < 262144); if (size > 65536) largeSchemas++;
        const f = fixture(mode, {request, package: candidate, reviewId, strictSchema: true, candidateArtifact: true});
        const rt = await mount(f);
        try {
          assert.equal(rt.receipt().candidateSchemaStrict, true);
          assert.equal(rt.receipt().candidateCompilerReady, true);
          assert.equal(rt.receipt().limits.maxOutputBytes, 65536);
          assert.equal(rt.receipt().startupMetadataBytes, size);
          assert.equal(rt.receipt().startupMetadataLimit, 262144);
          assert.equal(rt.ctx.get(plugin.READY_SERVICE).compilerReady, true);
          if (mode === 'generation') {
            const input = generationInput(candidate, {background: '', target_statement: SYNTHETIC_WRITING_DRAFT.target_statement});
            assert.equal(rt.value(await rt.call('lexicon_ielts_validate', {candidate: input})).status, 'structurally_valid');
          }
        } finally { await rt.cleanup(); }
      }
    }
    assert(largeSchemas > 0, 'The test must exercise an actual startup envelope above the tool-result cap');
    const f = fixture('generation', {request: requestWithParagraphs(['界'.repeat(24000)])});
    const rt = await mount(f);
    try {
      const result = await rt.call('lexicon_ielts_read', {document_id: 'article:p1'});
      assert.equal(result.isError, true);
      assert.match(JSON.stringify(result), /output_bound/);
      assert.equal(rt.receipt().limits.maxOutputBytes, 65536);
      assert.equal(rt.receipt().completedPerTool.lexicon_ielts_read, 0);
    } finally { await rt.cleanup(); }
  });
  await test('strict generation schema rejects missing unknown and switched question fields', async () => {
    const candidate = generationInput();
    const rt = await mount(fixture('generation', {strictSchema: true}));
    try {
      const missing = structuredClone(candidate); delete missing.writing_task2.prompt_draft;
      const unknown = structuredClone(candidate); unknown.reading_questions[0].extra_field = true;
      const switched = structuredClone(candidate); switched.reading_questions[0].type = 'identifying_information';
      for (const value of [missing, unknown, switched]) {
        const result = await rt.call('lexicon_ielts_validate', {candidate: value});
        assert.equal(result.isError, true); assert.match(result.content[0].text, /candidate_schema/);
      }
    } finally { await rt.cleanup(); }
  });
  await test('strict review schema publishes all nested fields and keeps correctness opaque', async () => {
    const rt = await mount(fixture('review', {strictSchema: true}));
    try {
      const schema = rt.ctx.tools.get('lexicon_ielts_validate').parameters.properties.candidate;
      const raw = JSON.stringify(schema);
      for (const field of ['option_reviews', 'source_ref_indices', 'not_given_review', 'paragraph_reviews', 'alternative_answers', 'writing_task2_review']) assert(raw.includes(`\"${field}\"`));
      for (const secret of ['SECRET_RATIONALE_Q1', 'PRIVATE_INFORMATION_EXPLANATION', 'PRIVATE_VIEW_EXPLANATION', 'PRIVATE_NG_JUSTIFICATION', 'PRIVATE_COMPLETION_EXPLANATION']) assert(!raw.includes(secret));
      const positive = rt.value(await rt.call('lexicon_ielts_validate', {candidate: review}));
      const disagreeing = structuredClone(review); disagreeing.reading_reviews[0].observed_answer = 'B';
      assert.deepEqual(rt.value(await rt.call('lexicon_ielts_validate', {candidate: disagreeing})), positive);
      assert.deepEqual(positive, {structurally_valid: true});
    } finally { await rt.cleanup(); }
  });
  await test('strict review schema rejects missing option fields unknown fields and switched kinds', async () => {
    const rt = await mount(fixture('review', {strictSchema: true}));
    try {
      const missing = structuredClone(review); delete missing.reading_reviews[0].option_reviews[0].source_ref_indices;
      const unknown = structuredClone(review); unknown.writing_task2_review.answer = 'private';
      const switched = structuredClone(review); switched.reading_reviews[0].type = 'identifying_information';
      for (const value of [missing, unknown, switched]) {
        const result = await rt.call('lexicon_ielts_validate', {candidate: value});
        assert.equal(result.isError, true); assert.match(result.content[0].text, /candidate_schema/);
      }
      const reversed = structuredClone(review); reversed.reading_reviews.reverse();
      const result = rt.value(await rt.call('lexicon_ielts_validate', {candidate: reversed}));
      assert.equal(result.ok, false, 'the fixed Python contract still owns array order');
    } finally { await rt.cleanup(); }
  });
  await test('review schema is invariant to hidden generator answers explanations and selected evidence', async () => {
    const changed = structuredClone(candidate);
    changed.reading_questions[0].answer = 'B';
    changed.reading_questions[0].explanation = 'GENERATOR_PRIVATE_CHANGED_EXPLANATION';
    changed.reading_questions[0].source_refs = structuredClone(changed.reading_questions[1].source_refs);
    const original = await mount(fixture('review', {strictSchema: true}));
    const alternate = await mount(fixture('review', {strictSchema: true, package: changed}));
    const withoutOpaqueHashes = value => {
      if (typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)) return '<opaque-sha256>';
      if (Array.isArray(value)) return value.map(withoutOpaqueHashes);
      if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, member]) => [key, withoutOpaqueHashes(member)]));
      return value;
    };
    try {
      const schemaA = original.ctx.tools.get('lexicon_ielts_validate').parameters.properties.candidate;
      const schemaB = alternate.ctx.tools.get('lexicon_ielts_validate').parameters.properties.candidate;
      assert.deepEqual(withoutOpaqueHashes(schemaA), withoutOpaqueHashes(schemaB));
      assert(!JSON.stringify(schemaB).includes('GENERATOR_PRIVATE_CHANGED_EXPLANATION'));
    } finally { await original.cleanup(); await alternate.cleanup(); }
  });
  await test('schema module digest is rechecked and cannot be selected by tool arguments', async () => {
    const candidate = generationInput();
    const f = fixture('generation', {strictSchema: true, copySchema: true}); const rt = await mount(f);
    try {
      const injected = await rt.call('lexicon_ielts_validate', {candidate, schemaModule: '/tmp/other.py'});
      assert.equal(injected.isError, true);
      writeFileSync(f.manifest.files.schemaModule.path, readFileSync(f.manifest.files.schemaModule.path, 'utf8') + '\n# replaced after initialization\n');
      const changed = await rt.call('lexicon_ielts_validate', {candidate});
      assert.equal(changed.isError, true); assert.match(changed.content[0].text, /input_digest/);
    } finally { await rt.cleanup(); }
  });
  await test('model cannot choose host mode review id package paths or unknown document', async () => {
    const rt = await mount(fixture());
    try {
      for (const [tool, args] of [
        ['lexicon_ielts_read', {document_id: '/etc/passwd'}],
        ['lexicon_ielts_retrieve', {path: '/etc/passwd'}],
        ['lexicon_ielts_validate', {candidate, mode: 'review'}],
        ['lexicon_ielts_validate', {candidate, reviewId: 'overwrite'}],
      ]) assert.equal((await rt.call(tool, args)).isError, true);
      const result = rt.value(await rt.call('lexicon_ielts_retrieve', {query: 'https://example.com'}));
      assert.equal(result.ok, false); assert.equal(result.error.code, 'lookup_target');
    } finally { await rt.cleanup(); }
  });
  await test('tool call budget counts admitted attempts and blocks the next execution', async () => {
    const rt = await mount(fixture('generation', {limits: {maxToolCalls: 1}}));
    try {
      rt.value(await rt.call('lexicon_ielts_retrieve', {}));
      const denied = await rt.call('lexicon_ielts_retrieve', {}); assert.equal(denied.isError, true); assert.match(denied.content[0].text, /tool_budget/);
      assert.equal(rt.receipt().allowedCount, 1); assert.equal(rt.receipt().deniedCount, 1);
    } finally { await rt.cleanup(); }
  });
  await test('a declared but absent continuation starts with no carried calls in every stage', async () => {
    for (const mode of ['generation', 'review', 'explanation_review']) {
      const f = fixture(mode, {strictSchema: true, candidateArtifact: true, continuation: true}); const rt = await mount(f);
      try {
        assert.equal(rt.receipt().carriedToolCalls, 0); assert.equal(rt.receipt().continuationSha256, null);
        rt.value(await rt.call('lexicon_ielts_read', {document_id: 'article:p1'}));
        assert.equal(rt.receipt().allowedCount, 1); assert.equal(rt.receipt().completedCount, 1);
      } finally { await rt.cleanup(); }
    }
  });
  await test('23 real readonly calls leave exactly one call under synthetic native continuation metadata', async () => {
    const {f, first, continuation, observedPath} = await continuationFixture({calls: 23});
    assert.equal(first.allowedCount, 23); assert.equal(first.completedPerTool.lexicon_ielts_read, 23);
    const observed = readFileSync(observedPath); const rt = await mount(f);
    try {
      assert.equal(rt.receipt().carriedToolCalls, 23); assert.equal(rt.receipt().continuationSha256, continuation.sha256);
      assert.equal(rt.receipt().allowedCount, 0); assert.equal(rt.receipt().completedCount, 0);
      rt.value(await rt.call('lexicon_ielts_validate', {candidate: review}));
      const denied = await rt.call('lexicon_ielts_read', {document_id: 'article:p1'});
      assert.equal(denied.isError, true); assert.match(denied.content[0].text, /tool_budget/);
      const receipt = rt.receipt();
      assert.equal(receipt.allowedCount, 1); assert.equal(receipt.completedCount, 1); assert.equal(receipt.deniedCount, 1);
      assert.equal(receipt.carriedToolCalls + receipt.allowedCount, 24);
      assert.deepEqual(receipt.perTool, {lexicon_ielts_retrieve: 0, lexicon_ielts_read: 0, lexicon_ielts_validate: 1});
      assert.deepEqual(readFileSync(observedPath), observed, 'first observed receipt stays unchanged');
    } finally { await rt.cleanup(); }
  });
  await test('both review modes accept identical receipt objects independent of JSON key order', async () => {
    for (const mode of ['review', 'explanation_review']) {
      const {f} = await continuationFixture({mode, mutateRuntime: runtime => {
        runtime.tool_receipt = Object.fromEntries(Object.entries(runtime.tool_receipt).reverse());
        runtime.tool_receipt.perTool = Object.fromEntries(Object.entries(runtime.tool_receipt.perTool).reverse());
      }});
      const rt = await mount(f);
      try {
        assert.equal(rt.receipt().carriedToolCalls, 1);
        rt.value(await rt.call('lexicon_ielts_validate', {candidate: mode === 'review' ? review : explanationReview}));
        rt.value(await rt.call('lexicon_ielts_read', {document_id: 'article:p1'}));
        assert.equal(rt.receipt().allowedCount, 2); assert.equal(rt.receipt().completedCount, 2);
      } finally { await rt.cleanup(); }
    }
  });
  await test('continuation rejects negative underreported exhausted or changed tool budgets', async () => {
    for (const mutateSeed of [
      seed => { seed.carriedToolCalls = -1; }, seed => { seed.carriedToolCalls = 1; },
      seed => { seed.carriedToolCalls = 24; }, seed => { seed.carriedToolCalls = 1.5; },
      seed => { seed.maxToolCalls = 25; }, seed => { seed.carriedToolCalls = '2'; },
    ]) {
      const {f} = await continuationFixture({calls: 2, mutateSeed});
      await assert.rejects(() => plugin.apply({}, f.config), /continuation_(identity|counts)/);
      assert(!existsSync(f.manifest.receiptPath));
    }
  });
  await test('continuation binds seed manifest session and both fixed original digests', async () => {
    for (const mutateSeed of [
      seed => { seed.manifestSha256 = '0'.repeat(64); }, seed => { seed.sessionId = 'session-unrelated'; },
      seed => { seed.runtimeReceiptSha256 = '0'.repeat(64); }, seed => { seed.pluginReceiptSha256 = '0'.repeat(64); },
      seed => { seed.runtimeReceiptPath = '/tmp/not-allowed.json'; },
    ]) {
      const {f} = await continuationFixture({mutateSeed});
      await assert.rejects(() => plugin.apply({}, f.config), /continuation_|input_digest|invalid_shape/);
    }
  });
  await test('continuation requires the exact reaped malformed tool JSON native failure', async () => {
    for (const [key, value] of [
      ['runtime_error_code', 'AUTHENTICATION_ERROR'], ['runtime_error_type', 'another_failure'],
      ['status', 'completed'], ['stage', 'generation'], ['native_attempt_count', 2], ['exit_code', 0],
      ['turn_count', 2], ['terminal_count', 0], ['errors', 1], ['malformed_frames', 1],
      ['process_reaped', false], ['stop_reason', 'timeout'], ['input_write_failed', true],
      ['candidate_exists_after_attempt', true], ['tool_calls', 2],
    ]) {
      const {f} = await continuationFixture({mutateRuntime: runtime => { runtime[key] = value; }});
      await assert.rejects(() => plugin.apply({}, f.config), /continuation_(runtime|counts)/);
    }
  });
  await test('continuation rejects any attempted validation or unfinished readonly execution', async () => {
    for (const mutateFirst of [
      first => { first.perTool.lexicon_ielts_validate = 1; first.allowedCount++; },
      first => { first.perTool.lexicon_ielts_validate = 1; first.completedPerTool.lexicon_ielts_validate = 1; first.allowedCount++; first.completedCount++; first.registrationChecks += 2; },
      first => { first.completedPerTool.lexicon_ielts_read = 0; first.completedCount = 0; },
      first => { first.registrationChecks++; },
      first => { first.perTool.lexicon_ielts_read = -1; },
      first => { first.perTool.unknown_tool = 1; },
    ]) {
      const {f} = await continuationFixture({mutateFirst});
      await assert.rejects(() => plugin.apply({}, f.config), /continuation_counts|invalid_shape/);
    }
  });
  await test('continuation refuses failed denied impersonated or candidate-bearing first attempts', async () => {
    for (const mutateFirst of [
      first => { first.failedCount = 1; }, first => { first.deniedCount = 1; },
      first => { first.identityFailures = 1; }, first => { first.globalGuard = false; },
      first => { first.registrationIdentityVerified = false; }, first => { first.candidateSchemaStrict = false; },
      first => { first.candidateSha256 = '0'.repeat(64); first.candidateBytes = 2; },
    ]) {
      const {f} = await continuationFixture({mutateFirst});
      await assert.rejects(() => plugin.apply({}, f.config), /continuation_plugin/);
    }
    const {f} = await continuationFixture(); writeFileSync(f.manifest.candidatePath, '{}');
    await assert.rejects(() => plugin.apply({}, f.config), /candidate_already_exists/);
  });
  await test('continuation requires matching native plugin and current module identities', async () => {
    for (const mutateRuntime of [
      runtime => { runtime.tool_receipt.allowedCount = 2; },
      runtime => { runtime.schema_module_sha256 = '0'.repeat(64); },
      runtime => { runtime.candidate_module_sha256 = '0'.repeat(64); },
      runtime => { runtime.corpus_sha256 = '0'.repeat(64); },
      runtime => { runtime.plugin_sha256 = '0'.repeat(64); },
    ]) {
      const {f} = await continuationFixture({mutateRuntime});
      await assert.rejects(() => plugin.apply({}, f.config), /continuation_(runtime|modules)|input_digest/);
    }
    for (const key of ['explanation_review_module_sha256', 'blind_review_input_sha256']) {
      const {f} = await continuationFixture({mode: 'explanation_review', mutateRuntime: runtime => { runtime[key] = '0'.repeat(64); }});
      await assert.rejects(() => plugin.apply({}, f.config), /continuation_modules/);
    }
  });
  await test('continuation cannot be nested or placed in the writable state directory', async () => {
    for (const mutateFirst of [
      first => { first.carriedToolCalls = 1; }, first => { first.continuationSha256 = '0'.repeat(64); },
    ]) {
      const {f} = await continuationFixture({mutateFirst});
      await assert.rejects(() => plugin.apply({}, f.config), /continuation_plugin/);
    }
    const {f: nested} = await continuationFixture({mutateRuntime: runtime => { runtime.recovery = {}; }});
    await assert.rejects(() => plugin.apply({}, nested.config), /continuation_runtime/);
    const misplaced = fixture('review', {continuation: true});
    misplaced.manifest.continuationPath = join(dirname(misplaced.manifest.receiptPath), 'continuation.json');
    misplaced.config.manifestSha256 = file(misplaced.config.manifestPath, misplaced.manifest).sha256;
    await assert.rejects(() => plugin.apply({}, misplaced.config), /continuation_path/);
  });
  await test('generation refuses continuation without completed retrieval', async () => {
    const {f} = await continuationFixture({mode: 'generation'});
    await assert.rejects(() => plugin.apply({}, f.config), /continuation_generation_retrieval/);
  });
  await test('seed and original receipts are pinned again before a resumed tool can execute', async () => {
    for (const key of ['continuation', 'runtimeReceipt', 'pluginReceipt']) {
      const fixture = await continuationFixture(); const rt = await mount(fixture.f);
      try {
        const path = fixture[key].path; writeFileSync(path, readFileSync(path, 'utf8') + '\n');
        const result = await rt.call('lexicon_ielts_read', {document_id: 'article:p1'});
        assert.equal(result.isError, true); assert.match(result.content[0].text, /continuation_changed/);
        assert.equal(rt.receipt().allowedCount, 0); assert.equal(rt.receipt().completedCount, 0); assert.equal(rt.receipt().deniedCount, 1);
      } finally { await rt.cleanup(); }
    }
  });
  await test('an initially absent seed cannot appear after plugin startup', async () => {
    const f = fixture('review', {strictSchema: true, candidateArtifact: true, continuation: true}); const rt = await mount(f);
    try {
      file(f.manifest.continuationPath, {});
      const result = await rt.call('lexicon_ielts_read', {document_id: 'article:p1'});
      assert.equal(result.isError, true); assert.match(result.content[0].text, /continuation_changed/);
      assert.equal(rt.receipt().allowedCount, 0); assert.equal(rt.receipt().carriedToolCalls, 0);
    } finally { await rt.cleanup(); }
  });
  await test('continuation seed and original receipts reject symlinks and nonregular oversized seed files', async () => {
    for (const key of ['continuation', 'runtimeReceipt', 'pluginReceipt']) {
      const current = await continuationFixture(); const path = current[key].path;
      renameSync(path, `${path}.observed`); symlinkSync(`${path}.observed`, path);
      await assert.rejects(() => plugin.apply({}, current.f.config), /input_unavailable/);
    }
    for (const kind of ['directory', 'oversized']) {
      const {f} = await continuationFixture(); const path = f.manifest.continuationPath;
      renameSync(path, `${path}.observed`);
      if (kind === 'directory') mkdirSync(path); else writeFileSync(path, ' '.repeat(4097));
      await assert.rejects(() => plugin.apply({}, f.config), /input_file_bound/);
    }
  });
  await test('input byte budget rejects oversized candidate without execution', async () => {
    const rt = await mount(fixture('generation', {limits: {maxInputBytes: 64}}));
    try { const r = await rt.call('lexicon_ielts_validate', {candidate}); assert.equal(r.isError, true); assert.match(r.content[0].text, /input_bound/); }
    finally { await rt.cleanup(); }
  });
  await test('fixed file digest is rechecked before every subprocess', async () => {
    const f = fixture('generation', {copyRequest: true}); const rt = await mount(f);
    try {
      writeFileSync(f.manifest.files.request.path, JSON.stringify({...request, article_id: 'replaced'}));
      const r = await rt.call('lexicon_ielts_retrieve', {}); assert.equal(r.isError, true); assert.match(r.content[0].text, /input_digest/);
    } finally { await rt.cleanup(); }
  });
  await test('Python subprocess receives no inherited secret or PYTHONPATH', async () => {
    process.env.LEXICON_SELFTEST_SECRET = 'synthetic-environment-probe'; process.env.DEEPSEEK_API_KEY = 'synthetic-never-forward'; process.env.PYTHONPATH = '/not/a/real/module/path';
    try { const rt = await mount(fixture('generation', {probeEnvironment: true})); try { rt.value(await rt.call('lexicon_ielts_retrieve', {})); } finally { await rt.cleanup(); } }
    finally { delete process.env.LEXICON_SELFTEST_SECRET; delete process.env.DEEPSEEK_API_KEY; delete process.env.PYTHONPATH; }
  });
  await test('fixed Python execution timeout kills and waits for child completion', async () => {
    const rt = await mount(fixture('generation', {delay: true, limits: {timeoutMs: 500}}));
    try {
      const before = Date.now(); const result = await rt.call('lexicon_ielts_validate', {candidate: {...candidate, _delay: true}});
      assert.equal(result.isError, true); assert.match(result.content[0].text, /bridge_timeout/); assert(Date.now() - before < 1800);
    } finally { await rt.cleanup(); }
  });
  await test('AbortSignal cancels a running child without leaving success', async () => {
    const rt = await mount(fixture('generation', {delay: true}));
    try {
      const controller = new AbortController();
      const promise = rt.call('lexicon_ielts_validate', {candidate: {...candidate, _delay: true}}, undefined, controller.signal);
      setTimeout(() => controller.abort(), 100);
      const result = await promise; assert.equal(result.isError, true); assert.equal(rt.receipt().completedCount, 0);
    } finally { await rt.cleanup(); }
  });
  await test('output byte budget rejects oversized frozen document output', async () => {
    const rt = await mount(fixture('generation', {limits: {maxOutputBytes: 1800}}));
    try {
      const result = await rt.call('lexicon_ielts_read', {document_id: 'ielts.reading.multiple_choice'});
      assert.equal(result.isError, true); assert.match(result.content[0].text, /output_bound/);
    } finally { await rt.cleanup(); }
  });
  await test('content-free receipt never contains article candidate review or environment text', async () => {
    const f = fixture(); const rt = await mount(f);
    try {
      rt.value(await rt.call('lexicon_ielts_validate', {candidate})); const raw = readFileSync(f.manifest.receiptPath, 'utf8');
      for (const value of ['solar panels', 'SECRET_RATIONALE', 'PRIVATE_REVIEW', 'synthetic-never-forward', 'selected_text', 'apiKey']) assert(!raw.includes(value));
      assert.equal(rt.receipt().perTool.lexicon_ielts_validate, 1);
    } finally { await rt.cleanup(); }
  });
  await test('legacy delivery stays explicit in receipts and never concludes a native turn', async () => {
    for (const candidateDelivery of [undefined, 'assistant_digest_confirmation']) {
      const f = fixture('generation', {strictSchema: true, candidateArtifact: true,
        ...(candidateDelivery === undefined ? {} : {candidateDelivery})});
      const rt = await mount(f);
      try {
        const result = await rt.call('lexicon_ielts_validate', {candidate: generationInput()});
        rt.value(result); assert.notEqual(result.concludesTurn, true);
        assert.equal(rt.receipt().candidateDelivery, 'assistant_digest_confirmation');
        assert.equal(rt.receipt().nativeToolConclusion, null);
      } finally { await rt.cleanup(); }
    }
  });
  await test('candidate delivery rejects unknown modes and native mode without an artifact path', async () => {
    for (const candidateDelivery of ['', null, false, 'assistant', 'native-tool-conclusion']) {
      const f = fixture('generation', {candidateDelivery});
      await assert.rejects(() => plugin.apply({}, f.config), /invalid_candidate_delivery/);
    }
    const f = fixture('generation', {candidateDelivery: 'native_tool_conclusion'});
    await assert.rejects(() => plugin.apply({}, f.config), /native_candidate_required/);
  });
  await test('native validation waits for the matching real Session result before committing its receipt', async () => {
    const rt = await nativeMount();
    try {
      const call = await rt.invoke('lexicon_ielts_validate', {candidate: generationInput()});
      const value = rt.value(call.result); assert.equal(call.result.concludesTurn, true);
      assert.equal(rt.receipt().nativeToolConclusion, null);
      const event = call.append(); const receipt = rt.receipt();
      assert.deepEqual(receipt.nativeToolConclusion, {
        schema: 'lexicon.ielts.native-tool-conclusion.v1', sessionId: rt.session.id, turnId: 1, stepId: 1,
        callId: call.callEvent.data.callId, callEventSeq: call.callEvent.seq, eventSeq: event.seq,
        toolName: 'lexicon_ielts_validate', allowedOrdinal: 1,
        candidateSha256: value.candidate_sha256, candidateBytes: statSync(rt.f.manifest.candidatePath).size,
        toolsResultObserved: true, sessionEventCommitted: true,
      });
      assert.equal(receipt.candidateSha256, value.candidate_sha256);
      assert.equal(receipt.registrationChecks, 2);
      const raw = JSON.stringify(receipt);
      assert(!raw.includes('solar panels')); assert(!raw.includes('candidate_confirmation_verified'));
    } finally { await rt.cleanup(); }
  });
  await test('native delivery also concludes structurally valid blind and explanation reviews', async () => {
    for (const mode of ['review', 'explanation_review']) {
      const rt = await nativeMount(fixture(mode, {strictSchema: true, candidateArtifact: true, candidateDelivery: 'native_tool_conclusion'}));
      try {
        const call = await rt.invoke('lexicon_ielts_validate', {candidate: structuredClone(mode === 'review' ? review : explanationReview)});
        rt.value(call.result); call.append();
        assert.equal(rt.receipt().nativeToolConclusion.toolName, 'lexicon_ielts_validate');
        assert.equal(call.result.concludesTurn, true);
      } finally { await rt.cleanup(); }
    }
  });
  await test('successful native delivery closes admission before the Session append and preserves candidate bytes', async () => {
    for (const appendFirst of [false, true]) {
      const rt = await nativeMount();
      try {
        const call = await rt.invoke('lexicon_ielts_validate', {candidate: generationInput()}); rt.value(call.result);
        const before = readFileSync(rt.f.manifest.candidatePath);
        if (appendFirst) call.append();
        const tail = await rt.invoke('lexicon_ielts_read', {document_id: 'article:p1'});
        assert.equal(tail.result.isError, true); assert.match(tail.result.content[0].text, /terminal_closed/);
        assert.equal(rt.receipt().allowedCount, 1); assert.equal(rt.receipt().deniedCount, 1);
        assert.deepEqual(readFileSync(rt.f.manifest.candidatePath), before);
        if (!appendFirst) call.append();
        assert.equal(rt.receipt().nativeToolConclusion.allowedOrdinal, 1);
      } finally { await rt.cleanup(); }
    }
  });
  await test('a failed native validation can be corrected without accepting the earlier attempt', async () => {
    const rt = await nativeMount(fixture('review', {strictSchema: true, candidateArtifact: true, candidateDelivery: 'native_tool_conclusion'}));
    try {
      const invalid = structuredClone(review); invalid.writing_task2_review.instruction_alignment.instruction_quote = 'not in prompt';
      const failed = await rt.invoke('lexicon_ielts_validate', {candidate: invalid}, {callId: 'reused-call', step: 1});
      assert.equal(rt.value(failed.result).ok, false); assert.notEqual(failed.result.concludesTurn, true); failed.append();
      assert.equal(rt.receipt().nativeToolConclusion, null);
      const success = await rt.invoke('lexicon_ielts_validate', {candidate: review}, {callId: 'reused-call', step: 2});
      rt.value(success.result); success.append();
      const receipt = rt.receipt(); assert.equal(receipt.failedCount, 1);
      assert.equal(receipt.nativeToolConclusion.stepId, 2);
      assert.equal(receipt.nativeToolConclusion.callEventSeq, success.callEvent.seq);
      assert.equal(receipt.nativeToolConclusion.allowedOrdinal, 2);
    } finally { await rt.cleanup(); }
  });
  await test('native dispatch needs a real live call event with matching arguments', async () => {
    for (const kind of ['agentless', 'fake-session', 'no-call', 'wrong-arguments']) {
      const rt = await nativeMount();
      try {
        if (kind === 'wrong-arguments') rt.session.append('tool/call', {turn: 1, step: 1, callId: `selftest-${caseNumber}`,
          name: 'lexicon_ielts_read', arguments: JSON.stringify({document_id: 'article:p2'})});
        const agent = kind === 'agentless' ? undefined : kind === 'fake-session' ? {session: {id: rt.session.id, eventAt() { return {}; }}} : rt.agent;
        const result = await rt.call('lexicon_ielts_read', {document_id: 'article:p1'}, agent);
        assert.equal(result.isError, true); assert.match(result.content[0].text, /native_call_unbound/);
        assert.equal(rt.receipt().allowedCount, 0); assert.equal(rt.receipt().nativeToolConclusion, null);
      } finally { await rt.cleanup(); }
    }
  });
  await test('synthetic emitted Session events cannot replace an actual accepted append', async () => {
    const rt = await nativeMount();
    try {
      const call = await rt.invoke('lexicon_ielts_validate', {candidate: generationInput()}); rt.value(call.result);
      const fake = Object.freeze({type: 'tool/result', seq: rt.session.seq, time: Date.now(),
        data: {turn: 1, step: 1, message: createToolResultMessage({callId: call.callEvent.data.callId, content: call.result.content, isError: false})},
        sourceEventSeqs: [call.callEvent.seq], surfaceOp: 'append'});
      rt.ctx.emit('session/event', rt.session, fake);
      assert.equal(rt.receipt().nativeToolConclusion, null);
      const event = call.append();
      assert.equal(rt.receipt().nativeToolConclusion.eventSeq, event.seq);
    } finally { await rt.cleanup(); }
  });
  await test('a same-id result in another real Session cannot conclude the originating Session', async () => {
    const rt = await nativeMount();
    try {
      const call = await rt.invoke('lexicon_ielts_validate', {candidate: generationInput()}); rt.value(call.result);
      const other = rt.ctx.sessions.create(`session-other-${caseNumber}`);
      other.append('tool/call', call.callEvent.data); call.append(undefined, other);
      assert.equal(rt.receipt().nativeToolConclusion, null);
      call.append(); assert.equal(rt.receipt().nativeToolConclusion.sessionId, rt.session.id);
    } finally { await rt.cleanup(); }
  });
  await test('native receipts reject mismatched committed result content, step or call reference', async () => {
    for (const kind of ['content', 'step', 'turn', 'reference', 'error']) {
      const rt = await nativeMount();
      try {
        const first = await rt.invoke('lexicon_ielts_read', {document_id: 'article:p1'}); rt.value(first.result); first.append();
        const call = await rt.invoke('lexicon_ielts_validate', {candidate: generationInput()}); rt.value(call.result);
        call.append((data, opts) => {
          if (kind === 'content') data.message.content = [{type: 'text', text: '{}'}];
          if (kind === 'step') data.step++;
          if (kind === 'turn') data.turn++;
          if (kind === 'reference') opts.sourceEventSeqs = [first.callEvent.seq];
          if (kind === 'error') data.message.isError = true;
        });
        assert.equal(rt.receipt().nativeToolConclusion, null); assert.equal(rt.receipt().candidateSha256, null);
      } finally { await rt.cleanup(); }
    }
  });
  await test('candidate bytes are verified again at the Session commit boundary', async () => {
    const rt = await nativeMount();
    try {
      const call = await rt.invoke('lexicon_ielts_validate', {candidate: generationInput()}); rt.value(call.result);
      writeFileSync(rt.f.manifest.candidatePath, '{}'); call.append();
      assert.equal(rt.receipt().nativeToolConclusion, null); assert.equal(rt.receipt().candidateSha256, null);
    } finally { await rt.cleanup(); }
  });
  await test('native conclusion refuses cancellation, thrown post hooks, blocked outcomes and rewritten results', async () => {
    for (const kind of ['cancel', 'throw', 'block', 'value', 'content']) {
      const rt = await nativeMount(); const controller = new AbortController();
      const dispose = rt.ctx.on('tools/post-execute', async (_exec, result, next) => {
        if (kind === 'cancel') controller.abort();
        if (kind === 'throw') throw new Error('synthetic post failure');
        if (kind === 'block') return {kind: 'block', feedback: [{type: 'text', text: 'synthetic post block'}]};
        if (kind === 'value') return {kind: 'accept', value: {...result.value, candidate_sha256: '0'.repeat(64)}};
        if (kind === 'content') return {kind: 'accept', content: [{type: 'text', text: '{}'}]};
        return next();
      });
      try {
        const call = await rt.invoke('lexicon_ielts_validate', {candidate: generationInput()}, {signal: controller.signal});
        call.append(); assert.equal(rt.receipt().nativeToolConclusion, null);
        assert.equal(rt.receipt().candidateSha256, null);
        if (['cancel', 'throw', 'block'].includes(kind)) assert.equal(call.result.isError, true);
      } finally { dispose(); await rt.cleanup(); }
    }
  });
  await test('cancellation between authoritative outcome and Session append cannot commit delivery', async () => {
    const rt = await nativeMount(); const controller = new AbortController();
    try {
      const call = await rt.invoke('lexicon_ielts_validate', {candidate: generationInput()}, {signal: controller.signal}); rt.value(call.result);
      controller.abort(); call.append();
      assert.equal(rt.receipt().nativeToolConclusion, null); assert.equal(rt.receipt().candidateSha256, null);
    } finally { await rt.cleanup(); }
  });
  await test('native read-only continuation preserves the budget and then commits its own validate result', async () => {
    const {f, first} = await continuationFixture({candidateDelivery: 'native_tool_conclusion', calls: 2});
    assert.equal(first.nativeToolConclusion, null);
    const rt = await nativeMount(f);
    try {
      const call = await rt.invoke('lexicon_ielts_validate', {candidate: review}); rt.value(call.result); call.append();
      const receipt = rt.receipt(); assert.equal(receipt.carriedToolCalls, 2);
      assert.equal(receipt.nativeToolConclusion.allowedOrdinal, 1);
      assert.equal(receipt.allowedCount, 1); assert.equal(receipt.candidateDelivery, 'native_tool_conclusion');
    } finally { await rt.cleanup(); }
  });
  await test('native continuation rejects missing, conflicting and already-concluded delivery receipts', async () => {
    for (const mutateFirst of [
      first => { delete first.candidateDelivery; },
      first => { first.candidateDelivery = 'assistant_digest_confirmation'; },
      first => { delete first.nativeToolConclusion; },
      first => { first.nativeToolConclusion = {toolsResultObserved: true}; },
    ]) {
      const {f} = await continuationFixture({candidateDelivery: 'native_tool_conclusion', mutateFirst});
      await assert.rejects(() => plugin.apply({}, f.config), /continuation_plugin/);
    }
  });
  await test('native recovery cannot consume its continuation in a different Session', async () => {
    const {f} = await continuationFixture({candidateDelivery: 'native_tool_conclusion'});
    const rt = await mount(f);
    const session = rt.ctx.sessions.create(`session-wrong-recovery-${caseNumber}`);
    const agent = {session}; const scope = createScope(rt.ctx.loader.resolve('lexicon-ielts').fiber.ctx, agent);
    try {
      const callId = `selftest-${caseNumber}`;
      session.append('tool/call', {turn: 1, step: 1, callId, name: 'lexicon_ielts_read',
        arguments: JSON.stringify({document_id: 'article:p1'})});
      const result = await rt.call('lexicon_ielts_read', {document_id: 'article:p1'}, agent);
      assert.equal(result.isError, true); assert.match(result.content[0].text, /native_call_unbound/);
      assert.equal(rt.receipt().allowedCount, 0);
    } finally { await scope.dispose(); await rt.cleanup(); }
  });
  await test('native guard refuses overlapping root executions before their authoritative results', async () => {
    const rt = await nativeMount();
    let signalEntered, release;
    const entered = new Promise(resolve => { signalEntered = resolve; });
    const blocked = new Promise(resolve => { release = resolve; });
    const dispose = rt.ctx.on('tools/post-execute', async (exec, _result, next) => {
      if (exec.callId === 'native-first') { signalEntered(); await blocked; }
      return next();
    });
    try {
      const firstPromise = rt.invoke('lexicon_ielts_read', {document_id: 'article:p1'}, {callId: 'native-first'});
      await entered;
      const second = await rt.invoke('lexicon_ielts_read', {document_id: 'article:p2'}, {callId: 'native-second'});
      assert.equal(second.result.isError, true); assert.match(second.result.content[0].text, /native_call_unbound/);
      release(); const first = await firstPromise; rt.value(first.result); first.append();
      assert.equal(rt.receipt().allowedCount, 1); assert.equal(rt.receipt().completedCount, 1);
      assert.equal(rt.receipt().deniedCount, 1);
    } finally { release(); dispose(); await rt.cleanup(); }
  });
  await test('native tool definitions and profile retain exclusive serial execution', async () => {
    const rt = await nativeMount();
    try {
      for (const name of plugin.TOOL_NAMES) assert.equal(rt.ctx.tools.get(name).isConcurrencySafe(), false);
      const rows = JSON.parse(plugin.buildHeadlessPatch({...rt.f.config, modelBaseURL: 'http://127.0.0.1:12345', candidateDelivery: 'native_tool_conclusion'}));
      const byId = new Map(rows.filter(row => row.id).map(row => [row.id, row]));
      assert.deepEqual(byId.get('tools').config, {mode: 'native', maxParallelSubCalls: 1});
      assert.deepEqual(byId.get('agent-loop').config, {agents: [], maxParallelToolCalls: 1});
      const persona = byId.get('system-prompt').config.personaPrefix;
      assert(persona.includes('concludes the native turn')); assert(!persona.includes('finish with exactly'));
      assert(rt.ctx.tools.get('lexicon_ielts_validate').description.includes('concludes this native turn'));
      assert.throws(() => plugin.buildHeadlessPatch({...rt.f.config, modelBaseURL: 'http://127.0.0.1:12345', candidateDelivery: null}), /invalid_candidate_delivery/);
    } finally { await rt.cleanup(); }
  });
  await test('headless patch pins native Messages adapter and blocks hidden services', async () => {
    const f = fixture(); const rows = JSON.parse(plugin.buildHeadlessPatch({...f.config, modelBaseURL: 'http://127.0.0.1:12345'}));
    const byId = new Map(rows.filter(r => r.id).map(r => [r.id, r]));
    assert.equal(byId.get('llm-deepseek').config.baseURL, 'http://127.0.0.1:12345');
    assert.equal(byId.get('llm-deepseek').config.retryPolicy.maxRetries, 0);
    assert.equal(byId.get('llm-deepseek').config.streamIdleTimeoutMs, 100000);
    for (const id of ['session-title-llm', 'session-telemetry-otel', 'agent-instructions', 'skill-filesystem', 'tool-bash', 'tool-fs', 'tool-web', 'subagent', 'workflow-ptc', 'llm-retry', 'session-log-deepseek', 'plugin-package-inventory-deepseek', 'compaction-basic']) assert.equal(byId.get(id).disabled, true);
    assert(byId.get('headless-runner').inject.includes(plugin.READY_SERVICE));
    const persona = byId.get('system-prompt').config.personaPrefix;
    for (const required of [...plugin.TOOL_NAMES, 'writing_task2_review', 'candidate_sha256', 'even when rejecting reading questions']) assert(persona.includes(required));
    assert(!persona.includes('Every stage must successfully call'));
    assert(persona.includes('Generation must successfully call'));
    assert(persona.includes('Every review must successfully call lexicon_ielts_validate'));
    assert(persona.includes('Examine all relevant supplied paragraphs even when no read tool call is needed'));
    assert.throws(() => plugin.buildHeadlessPatch({...f.config, modelBaseURL: 'https://api.deepseek.com'}), /loopback_required/);
    const profilePath = join(scratch, 'headless-patch.example.yml'); writeFileSync(profilePath, JSON.stringify(rows, null, 2));
  });
  await test('headless profile allows only flash or pro with bounded reasoning and output', async () => {
    const f = fixture();
    for (const model of ['deepseek-flash', 'deepseek-v4-pro']) for (const reasoningEffort of ['off', 'low', 'high', 'max']) {
      const rows = JSON.parse(plugin.buildHeadlessPatch({...f.config, modelBaseURL: 'http://127.0.0.1:12345', model, reasoningEffort}));
      const byId = new Map(rows.filter(r => r.id).map(r => [r.id, r]));
      assert.equal(byId.get('agent-default-model').config.model, model);
      assert.equal(byId.get('agent-default-model').config.reasoningEffort, reasoningEffort);
      assert.equal(byId.get('llm-deepseek').config.reasoningEffort, reasoningEffort);
      const limits = reasoningEffort === 'off' ? [8192, 100000] : [16384, 190000];
      assert.equal(byId.get('llm-deepseek').config.maxTokens, limits[0]);
      assert.equal(byId.get('llm-deepseek').config.models[0].maxTokens, limits[0]);
      assert.equal(byId.get('llm-deepseek').config.streamIdleTimeoutMs, limits[1]);
    }
    for (const changed of [{model: 'deepseek-chat'}, {reasoningEffort: 'ultra'}, {maxTokens: 8193},
                           {reasoningEffort: 'high', maxTokens: 8192}, {reasoningEffort: 'high', maxTokens: 16385},
                           {reasoningEffort: 'high', streamIdleTimeoutMs: 100000}, {streamIdleTimeoutMs: 190000},
                           {maxTokens: null}, {streamIdleTimeoutMs: null}])
      assert.throws(() => plugin.buildHeadlessPatch({...f.config, modelBaseURL: 'http://127.0.0.1:12345', ...changed}), /invalid_profile/);
  });
} finally {
  const receipt = {schema: 'lexicon.ielts.plugin-selftest.v1', runtime: 'DeepSeek Harness 0.2.1-rc.5', execution: 'installed Cordis loader and ToolRuntime', passed: cases.filter(c => c.passed).length, total: cases.length, modelCalls: 0, networkRequests: 0, cases};
  writeFileSync(join(scratch, 'selftest-receipt.json'), JSON.stringify(receipt, null, 2));
  console.log(JSON.stringify({passed: receipt.passed, total: receipt.total, modelCalls: 0, evidence: scratch}));
}
