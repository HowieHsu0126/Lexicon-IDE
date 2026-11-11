/** Restricted IELTS tools for the installed DeepSeek Harness (0.2.1-rc.5).
 * Host-owned manifests bind every input and Python module to bytes. Model input
 * supplies only retrieval arguments or a candidate object, never a command/path.
 * This plugin is a tool policy, not a sandbox against arbitrary host plugins.
 */
import { createHash, randomUUID } from 'node:crypto';
import { constants, openSync, closeSync, fstatSync, readSync, writeFileSync, renameSync, realpathSync, existsSync } from 'node:fs';
import { basename, dirname, isAbsolute, join, resolve } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { spawn } from 'node:child_process';

export const name = 'lexicon-ielts';
export const inject = ['tools'];
export const READY_SERVICE = 'lexiconIeltsReady';
export const TOOL_NAMES = Object.freeze(['lexicon_ielts_retrieve', 'lexicon_ielts_read', 'lexicon_ielts_validate']);
export function requiredStageTools(mode) {
  if (mode === 'generation') return [...TOOL_NAMES];
  if (mode === 'review' || mode === 'explanation_review') return ['lexicon_ielts_validate'];
  fail('invalid_mode');
}
const SHA = /^[a-f0-9]{64}$/;
const FILE_CAP = 2 * 1024 * 1024;
// Host-only startup metadata can contain a larger fixed schema. This does not
// change the model-visible read/retrieve/validate result or request budgets.
const READY_OUTPUT_CAP = 262144;
const LEGACY_DELIVERY = 'assistant_digest_confirmation';
const NATIVE_DELIVERY = 'native_tool_conclusion';
const DELIVERY_MODES = Object.freeze([LEGACY_DELIVERY, NATIVE_DELIVERY]);
const DEFAULT_LIMITS = Object.freeze({maxToolCalls: 24, maxInputBytes: 131072, maxOutputBytes: 65536, timeoutMs: 10000});
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const fail = code => { throw new Error(`lexicon_ielts:${code}`); };
function keys(value, required, optional = []) {
  if (!object(value) || required.some(key => !Object.hasOwn(value, key)) || Object.keys(value).some(key => ![...required, ...optional].includes(key))) fail('invalid_shape');
}
function absolute(value) {
  if (typeof value !== 'string' || !isAbsolute(value) || value.includes('\0')) fail('invalid_host_path');
  return value;
}
function boundedFile(path, cap, optional = false) {
  let fd;
  try {
    fd = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    const st = fstatSync(fd);
    if (!st.isFile() || st.size < 1 || st.size > cap) fail('input_file_bound');
    // The extra byte detects growth without allowing an unbounded fd read.
    const bytes = Buffer.alloc(st.size + 1);
    let length = 0;
    while (length < bytes.length) {
      const count = readSync(fd, bytes, length, bytes.length - length, null);
      if (count === 0) break;
      length += count;
    }
    if (length !== st.size) fail('input_file_bound');
    return bytes.subarray(0, length);
  } catch (error) {
    if (optional && fd === undefined && error.code === 'ENOENT') return null;
    if (error.message?.startsWith('lexicon_ielts:')) throw error;
    fail('input_unavailable');
  } finally { if (fd !== undefined) closeSync(fd); }
}
function pinned(spec, cap = FILE_CAP) {
  keys(spec, ['path', 'sha256']); absolute(spec.path);
  if (!SHA.test(spec.sha256)) fail('invalid_host_hash');
  const bytes = boundedFile(spec.path, cap);
  if (hash(bytes) !== spec.sha256) fail('input_digest');
  return bytes;
}
function frozen(value) {
  if (value && typeof value === 'object') { for (const member of Object.values(value)) frozen(member); Object.freeze(value); }
  return value;
}
function configOf(config) {
  keys(config, ['manifestPath', 'manifestSha256']);
  const manifestBytes = pinned({path: config.manifestPath, sha256: config.manifestSha256}, 32768);
  let manifest;
  try { manifest = JSON.parse(manifestBytes); } catch { fail('invalid_manifest_json'); }
  keys(manifest, ['schema', 'mode', 'python', 'runtimeRoot', 'files', 'receiptPath'], ['limits', 'reviewId', 'candidatePath', 'continuationPath', 'candidateDelivery']);
  if (manifest.schema !== 'lexicon.ielts.harness.manifest.v1' || !['generation', 'review', 'explanation_review'].includes(manifest.mode)) fail('invalid_manifest');
  if (manifest.candidateDelivery !== undefined && !DELIVERY_MODES.includes(manifest.candidateDelivery)) fail('invalid_candidate_delivery');
  manifest.candidateDelivery ??= LEGACY_DELIVERY;
  if (manifest.candidateDelivery === NATIVE_DELIVERY && manifest.candidatePath === undefined) fail('native_candidate_required');
  absolute(manifest.python); absolute(manifest.runtimeRoot); absolute(manifest.receiptPath);
  const required = ['contract', 'corpusModule', 'corpus', 'request'];
  if (manifest.mode !== 'generation') required.push('reviewModule', 'package');
  if (manifest.mode === 'explanation_review') required.push('explanationReviewModule', 'blindReview', 'schemaModule', 'candidateModule');
  keys(manifest.files, required, ['schemaModule', 'candidateModule', 'visualModule']);
  if (manifest.mode !== 'generation' && (typeof manifest.reviewId !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(manifest.reviewId))) fail('invalid_review_id');
  if (manifest.mode === 'generation' && Object.hasOwn(manifest, 'reviewId')) fail('unexpected_review_id');
  const limits = {...DEFAULT_LIMITS, ...manifest.limits};
  if (manifest.limits !== undefined) keys(manifest.limits, [], Object.keys(DEFAULT_LIMITS));
  for (const [key, max] of Object.entries({maxToolCalls: 100, maxInputBytes: 1048576, maxOutputBytes: 1048576, timeoutMs: 30000})) {
    if (!Number.isSafeInteger(limits[key]) || limits[key] < 1 || limits[key] > max) fail('invalid_budget');
  }
  for (const spec of Object.values(manifest.files)) pinned(spec);
  if (Object.values(manifest.files).some(spec => resolve(spec.path) === resolve(manifest.receiptPath)) || resolve(config.manifestPath) === resolve(manifest.receiptPath)) fail('receipt_input_collision');
  if (existsSync(manifest.receiptPath)) fail('receipt_already_exists');
  // Freeze the selected executable and output directory once; subprocesses never
  // inherit the user's shell, PYTHONPATH, API key, proxy, or credential variables.
  manifest.python = realpathSync(manifest.python);
  const receiptDirectory = realpathSync(dirname(manifest.receiptPath));
  manifest.receiptPath = join(receiptDirectory, manifest.receiptPath.split('/').at(-1));
  if (manifest.continuationPath !== undefined) {
    absolute(manifest.continuationPath);
    const inputDirectory = realpathSync(dirname(config.manifestPath));
    if (basename(inputDirectory) !== 'inputs' || inputDirectory === receiptDirectory ||
        manifest.continuationPath !== join(inputDirectory, 'continuation.json')) fail('continuation_path');
  }
  if (manifest.candidatePath !== undefined) {
    absolute(manifest.candidatePath);
    const candidateDirectory = realpathSync(dirname(manifest.candidatePath));
    manifest.candidatePath = join(candidateDirectory, manifest.candidatePath.split('/').at(-1));
    if (!Object.hasOwn(manifest.files, 'schemaModule')) fail('candidate_schema_required');
    if (!Object.hasOwn(manifest.files, 'candidateModule')) fail('candidate_compiler_required');
    if (candidateDirectory !== receiptDirectory || manifest.candidatePath === manifest.receiptPath ||
        Object.values(manifest.files).some(spec => realpathSync(spec.path) === manifest.candidatePath) ||
        realpathSync(config.manifestPath) === manifest.candidatePath) fail('candidate_output_collision');
    if (existsSync(manifest.candidatePath)) fail('candidate_already_exists');
  }
  const host = {...manifest, limits, manifestPath: config.manifestPath, manifestSha256: config.manifestSha256};
  return frozen({...host, continuation: continuationOf(host)});
}

function continuationOf(host) {
  const absent = {carriedToolCalls: 0, sha256: null, pins: [], sessionId: null};
  if (host.continuationPath === undefined) return absent;
  const bytes = boundedFile(host.continuationPath, 4096, true);
  if (bytes === null) return absent;
  if (!['generation', 'review', 'explanation_review'].includes(host.mode)) fail('continuation_mode');
  let seed, runtime, first;
  try { seed = JSON.parse(bytes); } catch { fail('continuation_json'); }
  keys(seed, ['schema', 'manifestSha256', 'sessionId', 'runtimeReceiptSha256', 'pluginReceiptSha256', 'carriedToolCalls', 'maxToolCalls']);
  if (seed.schema !== 'lexicon.ielts.continuation.v1' || seed.manifestSha256 !== host.manifestSha256 ||
      typeof seed.sessionId !== 'string' || !/^session-[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(seed.sessionId) ||
      !Number.isSafeInteger(seed.carriedToolCalls) || seed.carriedToolCalls < 0 ||
      seed.carriedToolCalls >= host.limits.maxToolCalls || seed.maxToolCalls !== host.limits.maxToolCalls) fail('continuation_identity');
  const attemptDirectory = join(dirname(host.receiptPath), 'attempt-01');
  const pins = [
    {path: host.continuationPath, sha256: hash(bytes)},
    {path: join(attemptDirectory, 'runtime-receipt.json'), sha256: seed.runtimeReceiptSha256},
    {path: join(attemptDirectory, 'plugin-receipt.json'), sha256: seed.pluginReceiptSha256},
  ];
  try { runtime = JSON.parse(pinned(pins[1])); first = JSON.parse(pinned(pins[2], 32768)); }
  catch (error) { if (error.message?.startsWith('lexicon_ielts:')) throw error; fail('continuation_json'); }
  if (!object(runtime) || !object(first) || !isDeepStrictEqual(runtime.tool_receipt, first) ||
      runtime.schema !== 'lexicon.ielts.runtime-receipt.v1' || runtime.status !== 'failed' ||
      runtime.stage !== host.mode || runtime.manifest_sha256 !== host.manifestSha256 || runtime.session_id !== seed.sessionId ||
      runtime.runtime_error_code !== 'MALFORMED_RESPONSE' || runtime.runtime_error_type !== 'tool_input_invalid_json' ||
      runtime.terminal !== 'error' || runtime.process_reaped !== true || runtime.stop_reason !== null ||
      runtime.input_write_failed !== false || runtime.candidate_exists_after_attempt !== false || Object.hasOwn(runtime, 'recovery') ||
      ['native_attempt_count', 'exit_code', 'turn_count', 'terminal_count'].some(key => runtime[key] !== 1) ||
      ['errors', 'malformed_frames'].some(key => runtime[key] !== 0)) fail('continuation_runtime');
  if (first.schema !== 'lexicon.ielts.harness-plugin-receipt.v1' || first.mode !== host.mode ||
      first.manifestSha256 !== host.manifestSha256 || !isDeepStrictEqual(first.limits, host.limits) ||
      !isDeepStrictEqual(first.toolNames, TOOL_NAMES) || !isDeepStrictEqual(first.requiredTools, requiredStageTools(host.mode)) ||
      !isDeepStrictEqual(runtime.required_tools, requiredStageTools(host.mode)) ||
      ['pluginReady', 'globalGuard', 'registrationIdentityVerified', 'candidateSchemaStrict', 'candidateCompilerReady'].some(key => first[key] !== true) ||
      ['failedCount', 'deniedCount', 'identityFailures', 'candidateBytes'].some(key => first[key] !== 0) ||
      first.candidateSha256 !== null || (Object.hasOwn(first, 'carriedToolCalls') && first.carriedToolCalls !== 0) ||
      (first.candidateDelivery ?? LEGACY_DELIVERY) !== host.candidateDelivery ||
      (first.nativeToolConclusion ?? null) !== null ||
      (host.candidateDelivery === NATIVE_DELIVERY && (!Object.hasOwn(first, 'nativeToolConclusion') || first.candidateDelivery !== NATIVE_DELIVERY)) ||
      (Object.hasOwn(first, 'continuationSha256') && first.continuationSha256 !== null) ||
      first.startupMetadataLimit !== READY_OUTPUT_CAP || !Number.isSafeInteger(first.startupMetadataBytes) ||
      first.startupMetadataBytes < 1 || first.startupMetadataBytes > READY_OUTPUT_CAP) fail('continuation_plugin');
  if (host.mode === 'generation' && ['lexicon_ielts_retrieve','lexicon_ielts_read'].some(name => first.completedPerTool?.[name] < 1)) fail('continuation_generation_retrieval');
  const identities = [
    ['schemaModule', 'schema_module_sha256', 'schemaModuleSha256'],
    ['candidateModule', 'candidate_module_sha256', 'candidateModuleSha256'],
    ...(host.files.visualModule ? [['visualModule', 'visual_module_sha256', 'visualModuleSha256']] : []),
    ...(host.mode === 'explanation_review' ? [
      ['explanationReviewModule', 'explanation_review_module_sha256', 'explanationReviewModuleSha256'],
      ['blindReview', 'blind_review_input_sha256', 'blindReviewInputSha256'],
    ] : []),
  ];
  if (runtime.corpus_sha256 !== host.files.corpus.sha256 || identities.some(([file, nativeField, pluginField]) =>
    !host.files[file] || runtime[nativeField] !== host.files[file].sha256 || first[pluginField] !== host.files[file].sha256)) fail('continuation_modules');
  const ownModule = {path: fileURLToPath(import.meta.url), sha256: runtime.plugin_sha256};
  pinned(ownModule); pins.push(ownModule);
  for (const key of ['perTool', 'completedPerTool']) {
    keys(first[key], TOOL_NAMES);
    if (Object.values(first[key]).some(count => !Number.isSafeInteger(count) || count < 0)) fail('continuation_counts');
  }
  const used = first.perTool.lexicon_ielts_read + first.perTool.lexicon_ielts_retrieve;
  if (!isDeepStrictEqual(first.perTool, first.completedPerTool) || first.perTool.lexicon_ielts_validate !== 0 ||
      first.allowedCount !== used || first.completedCount !== used || runtime.tool_calls !== used ||
      first.registrationChecks !== 2 * used || seed.carriedToolCalls !== used) fail('continuation_counts');
  return {carriedToolCalls: used, completedPerTool: {...first.completedPerTool}, sha256: pins[0].sha256, pins, sessionId: seed.sessionId};
}

function checkContinuation(host) {
  if (host.continuationPath === undefined) return;
  if (host.continuation.sha256 === null) {
    if (boundedFile(host.continuationPath, 4096, true) !== null) fail('continuation_changed');
    return;
  }
  for (const pin of host.continuation.pins) pinned(pin);
}

// Only the host supplies this program. No candidate text is interpolated into it.
const BRIDGE = String.raw`
import sys, os, json, hashlib, types, re, stat, tempfile
MAX=2097152
class BridgeError(Exception):
    def __init__(self, code): self.code=code

def pairs(items):
    result={}
    for k,v in items:
        if k in result: raise BridgeError('duplicate_json_key')
        result[k]=v
    return result

def decode(raw):
    return json.loads(raw, object_pairs_hook=pairs, parse_constant=lambda _: (_ for _ in ()).throw(BridgeError('invalid_json')))

def read(spec):
    fd=os.open(spec['path'], os.O_RDONLY | getattr(os,'O_NOFOLLOW',0))
    try:
        size=os.fstat(fd).st_size
        if not stat.S_ISREG(os.fstat(fd).st_mode) or not 0<size<=MAX: raise BridgeError('input_file_bound')
        with os.fdopen(fd,'rb',closefd=False) as f: raw=f.read(MAX+1)
    finally: os.close(fd)
    if len(raw)!=size or hashlib.sha256(raw).hexdigest()!=spec['sha256']: raise BridgeError('input_digest')
    return raw

def module(name,spec):
    raw=read(spec)
    mod=types.ModuleType(name);mod.__file__=spec['path'];mod.__package__=''
    sys.modules[name]=mod
    exec(compile(raw,spec['path'],'exec'),mod.__dict__)
    return mod

def error_data(error):
    code=getattr(error,'code','bridge_failure')
    if not isinstance(code,str) or re.fullmatch(r'[A-Za-z0-9_]{1,64}',code) is None: code='bridge_failure'
    result={'ok':False,'error':{'code':code}}
    path=getattr(error,'path',None)
    if isinstance(path,str) and re.fullmatch(r'[A-Za-z0-9_.\[\]-]{1,160}',path): result['error']['path']=path
    if code in ('candidate_quote_ambiguous','candidate_answer_ambiguous','candidate_visual_number_ambiguous','candidate_visual_number_missing'):
        result['error']['hint']='Use lexicon_ielts_read with the quote and provide the intended full reference.'
    return result

def store_candidate(host, candidate):
    raw=json.dumps(candidate,ensure_ascii=False,allow_nan=False,sort_keys=True,separators=(',',':')).encode('utf-8')
    if not 0<len(raw)<=host['candidateByteLimit']: raise BridgeError('candidate_bound')
    path=host['candidatePath']
    fd,temporary=tempfile.mkstemp(prefix='.compiled-candidate-',suffix='.tmp',dir=os.path.dirname(path))
    try:
        with os.fdopen(fd,'wb') as stream:
            stream.write(raw);stream.flush();os.fsync(stream.fileno())
        os.replace(temporary,path)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)
    return {'sha256':hashlib.sha256(raw).hexdigest(),'bytes':len(raw)}

def article_read(request, arguments, contract):
    if not isinstance(arguments,dict) or not {'document_id'} <= set(arguments) <= {'document_id','quote'}:
        raise BridgeError('invalid_arguments')
    document_id=arguments['document_id']
    paragraphs={'article:'+p['paragraph_id']:p for p in request['paragraphs']}
    if not isinstance(document_id,str) or document_id not in paragraphs:
        raise BridgeError('invalid_document_id')
    paragraph=paragraphs[document_id]
    identity={'document_id':document_id,'request_sha256':contract.canonical_sha256(request),'paragraph_id':paragraph['paragraph_id']}
    if 'quote' not in arguments:
        return {'schema':'lexicon.ielts.article.read.v1',**identity,'start':paragraph['start'],'end':paragraph['end'],'text':paragraph['text']}
    quote=arguments['quote']
    if not isinstance(quote,str) or not 1<=contract.utf16_length(quote)<=2000:
        raise BridgeError('quote_bound')
    # This expression rejects lookup targets; matching against the article below
    # is exclusively literal str.find, with no regex or normalisation semantics.
    if re.search(r'(?:[a-z][a-z0-9+.-]*://|(?:file|mailto|data):|(?:^|\s)(?:/|~[/\\]|\.\.?[/\\]|[a-z]:[/\\]))',quote,re.I):
        raise BridgeError('quote_lookup_target')
    text=paragraph['text'];cursor=0;ranges=[]
    while True:
        index=text.find(quote,cursor)
        if index<0: break
        if len(ranges)==8: raise BridgeError('quote_match_bound')
        start=paragraph['start']+contract.utf16_length(text[:index])
        ranges.append({'paragraph_id':paragraph['paragraph_id'],'start':start,'end':start+contract.utf16_length(quote),'quote':quote})
        # Include overlapping literal matches instead of silently selecting a
        # preferred occurrence; both generation and review see the same ranges.
        cursor=index+1
    if not ranges: raise BridgeError('quote_not_found')
    return {'schema':'lexicon.ielts.article.matches.v1',**identity,'ranges':ranges,'match_count':len(ranges)}

try:
    raw=sys.stdin.buffer.read(MAX+1)
    if len(raw)>MAX: raise BridgeError('input_bound')
    envelope=decode(raw)
    host=envelope['host']; files=host['files'];operation=envelope['operation'];arguments=envelope.get('arguments',{})
    contract=module('ielts_contract',files['contract'])
    if 'visualModule' in files: module('ielts_visual',files['visualModule'])
    corpus_module=module('ielts_corpus',files['corpusModule'])
    corpus=corpus_module.load(files['corpus']['path'],expected_sha256=files['corpus']['sha256'])
    request=decode(read(files['request']));contract.validate_request(request)
    compiler=module('ielts_candidate',files['candidateModule']) if 'candidateModule' in files else None
    schemas=module('ielts_json_schema',files['schemaModule']) if 'schemaModule' in files else None
    review=None;package=None;explanation_review=None;blind_review=None
    if host['mode']!='generation':
        review=module('ielts_review',files['reviewModule'])
        package=decode(read(files['package']));contract.validate_package(request,package)
    if host['mode']=='explanation_review':
        explanation_review=module('ielts_explanation_review',files['explanationReviewModule'])
        blind_review=decode(read(files['blindReview']))
        if not isinstance(blind_review,dict): raise BridgeError('blind_review_required')
        if review.validate_review(request,package,blind_review,blind_review.get('review_id'))['status']!='eligible_for_caller_publication':
            raise BridgeError('blind_review_rejected')
    if operation=='ready':
        documents=decode(read(files['corpus']))['documents']
        result={'schema':'lexicon.ielts.plugin-ready.v1','document_ids':[d['document_id'] for d in documents], 'article_document_ids':['article:'+p['paragraph_id'] for p in request['paragraphs']], 'modules':list(corpus_module.MODULES),'question_types':list(corpus_module.QUESTION_TYPES),'candidate_compiler_ready':compiler is not None and callable(getattr(compiler,'compile_candidate',None))}
        if 'schemaModule' in files:
            if host['mode']=='generation': result['candidate_schema']=schemas.generation_schema(request)
            elif host['mode']=='review': result['candidate_schema']=schemas.review_schema(request,package,host['reviewId'])
            else: result['candidate_schema']=explanation_review.explanation_review_schema(request,package,blind_review,host['reviewId'])
    elif operation=='read' and isinstance(arguments,dict) and isinstance(arguments.get('document_id'),str) and arguments['document_id'].startswith('article:'):
        result=article_read(request,arguments,contract)
    elif operation in ('retrieve','read'):
        result=corpus_module.handle_tool_request(corpus,{'tool':operation,'arguments':arguments})
    elif operation=='validate':
        if set(arguments)!= {'candidate'}: raise BridgeError('invalid_arguments')
        candidate=compiler.compile_candidate(request,arguments['candidate'],host['mode']) if compiler else arguments['candidate']
        if host['mode']=='generation': result=contract.validate_package(request,candidate)
        elif host['mode']=='review':
            review.validate_review(request,package,candidate,host['reviewId'])
            # A review cannot probe the generator's hidden answers through the
            # semantic gate. Agreement, blockers and gate status stay host-only.
            result={'structurally_valid':True}
        else:
            explanation_review.validate_explanation_review(request,package,blind_review,candidate,host['reviewId'])
            # A well-formed rejection is a completed audit, not a tool error.
            # The parent combines the two frozen audit decisions before ready.
            result={'structurally_valid':True}
    else: raise BridgeError('unsupported_operation')
    response={'ok':True,'value':result}
    if operation=='validate' and host.get('candidatePath'):
        response['artifact']=store_candidate(host,candidate)
except BaseException as error:
    response=error_data(error)
sys.stdout.write(json.dumps(response,ensure_ascii=False,allow_nan=False,separators=(',',':')))
`;

function bridge(host, operation, args, signal) {
  if (signal?.aborted) return Promise.reject(new Error('lexicon_ielts:cancelled'));
  // Recheck fixed inputs before each launch. The child also hashes the exact
  // bytes that it compiles/parses, so no unchecked module bytes are executed.
  pinned({path: host.manifestPath, sha256: host.manifestSha256}, 32768);
  checkContinuation(host);
  for (const spec of Object.values(host.files)) pinned(spec);
  const stdin = JSON.stringify({host: {mode: host.mode, files: host.files, reviewId: host.reviewId,
    candidatePath: host.candidatePath, candidateByteLimit: Math.min(host.limits.maxInputBytes, 200000)}, operation, arguments: args});
  const outputLimit = operation === 'ready' ? READY_OUTPUT_CAP : host.limits.maxOutputBytes;
  if (Buffer.byteLength(stdin) > FILE_CAP) return Promise.reject(new Error('lexicon_ielts:input_bound'));
  return new Promise((resolvePromise, reject) => {
    let child;
    try {
      child = spawn(host.python, ['-I', '-B', '-c', BRIDGE], {
        shell: false, cwd: dirname(host.receiptPath),
        env: {PATH: '/usr/bin:/bin', LANG: 'en_US.UTF-8', LC_ALL: 'en_US.UTF-8', PYTHONIOENCODING: 'utf-8'},
        stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true,
      });
    } catch { reject(new Error('lexicon_ielts:bridge_spawn')); return; }
    let chunks = [], bytes = 0, stderrBytes = 0, reason;
    const stop = code => { reason ??= code; child.kill('SIGKILL'); };
    const cancel = () => stop('cancelled');
    const timer = setTimeout(() => stop('bridge_timeout'), host.limits.timeoutMs);
    signal?.addEventListener('abort', cancel, {once: true});
    if (signal?.aborted) cancel();
    child.stdout.on('data', chunk => { bytes += chunk.length; if (bytes > outputLimit) stop('output_bound'); else chunks.push(chunk); });
    child.stderr.on('data', chunk => { stderrBytes += chunk.length; if (stderrBytes > 4096) stop('diagnostic_bound'); });
    child.on('error', () => { reason ??= 'bridge_spawn'; });
    child.stdin.on('error', () => { reason ??= 'bridge_stdin'; });
    child.on('close', code => {
      clearTimeout(timer); signal?.removeEventListener('abort', cancel);
      if (reason || code !== 0 || stderrBytes > 0) { reject(new Error(`lexicon_ielts:${reason ?? 'bridge_failure'}`)); return; }
      try {
        const response = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        chunks = [];
        if (!object(response) || typeof response.ok !== 'boolean') fail('bridge_response');
        if (operation === 'ready') response.startupMetadataBytes = bytes;
        resolvePromise(response);
      } catch { reject(new Error('lexicon_ielts:bridge_response')); }
    });
    child.stdin.end(stdin);
  });
}

function schema(properties, required = []) {
  return {type: 'object', properties, required, additionalProperties: false};
}
function validateArgs(operation, args, ready) {
  if (operation === 'retrieve') {
    keys(args, [], ['query', 'module', 'question_type', 'limit']);
    if (args.query !== undefined && (typeof args.query !== 'string' || args.query.length > 240)) fail('invalid_query');
    if (args.module !== undefined && !ready.modules.includes(args.module)) fail('invalid_module');
    if (args.question_type !== undefined && !ready.question_types.includes(args.question_type)) fail('invalid_question_type');
    if (args.limit !== undefined && (!Number.isInteger(args.limit) || args.limit < 1 || args.limit > 8)) fail('invalid_limit');
  } else if (operation === 'read') {
    keys(args, ['document_id'], ['quote']);
    const article = ready.article_document_ids.includes(args.document_id);
    if (!article && !ready.document_ids.includes(args.document_id)) fail('invalid_document_id');
    if (Object.hasOwn(args, 'quote')) {
      if (!article) fail('quote_article_only');
      if (typeof args.quote !== 'string' || args.quote.length < 1 || args.quote.length > 2000) fail('quote_bound');
    }
  } else {
    keys(args, ['candidate']);
    if (!object(args.candidate)) fail('invalid_candidate');
  }
}

export async function apply(ctx, config) {
  const host = configOf(config);
  const scopeModule = await import(pathToFileURL(join(host.runtimeRoot, '@deepseek-ai/dsh-scope/lib/index.js')).href);
  const schemaRuntime = await import(pathToFileURL(join(host.runtimeRoot, '@deepseek-ai/dsh-tools/lib/index.js')).href);
  const sessionRuntime = host.candidateDelivery === NATIVE_DELIVERY
    ? await import(pathToFileURL(join(host.runtimeRoot, '@deepseek-ai/dsh-session/lib/index.js')).href) : undefined;
  if (scopeModule.scopeOf(ctx) !== undefined) fail('global_scope_required');
  const owned = new Map();
  const permits = new WeakSet();
  const receipt = {
    schema: 'lexicon.ielts.harness-plugin-receipt.v1', pluginReady: false,
    mode: host.mode, manifestSha256: host.manifestSha256,
    requiredTools: requiredStageTools(host.mode),
    globalGuard: true, registrationIdentityVerified: false,
    candidateSchemaStrict: false, schemaModuleSha256: host.files.schemaModule?.sha256 ?? null,
    candidateCompilerReady: false, candidateModuleSha256: host.files.candidateModule?.sha256 ?? null,
    ...(host.files.visualModule ? {visualModuleSha256: host.files.visualModule.sha256} : {}),
    ...(host.mode === 'explanation_review' ? {
      explanationReviewModuleSha256: host.files.explanationReviewModule.sha256,
      blindReviewInputSha256: host.files.blindReview.sha256,
    } : {}),
    startupMetadataBytes: null, startupMetadataLimit: READY_OUTPUT_CAP,
    candidateSha256: null, candidateBytes: 0,
    candidateDelivery: host.candidateDelivery, nativeToolConclusion: null,
    carriedToolCalls: host.continuation.carriedToolCalls, continuationSha256: host.continuation.sha256,
    allowedCount: 0, deniedCount: 0, completedCount: 0, failedCount: 0,
    registrationChecks: 0, identityFailures: 0,
    perTool: Object.fromEntries(TOOL_NAMES.map(tool => [tool, 0])),
    completedPerTool: Object.fromEntries(TOOL_NAMES.map(tool => [tool, 0])),
    ...(host.mode === 'generation' && host.continuation.sha256 !== null ? {carriedCompletedPerTool: {...host.continuation.completedPerTool}} : {}),
    limits: host.limits, toolNames: [...TOOL_NAMES],
  };
  const save = () => {
    const temporary = `${host.receiptPath}.${randomUUID()}.tmp`;
    writeFileSync(temporary, JSON.stringify(receipt, null, 2) + '\n', {mode: 0o600, flag: 'wx'});
    renameSync(temporary, host.receiptPath);
  };
  const confirmCandidate = artifact => {
    keys(artifact, ['sha256', 'bytes']);
    if (!SHA.test(artifact.sha256) || !Number.isSafeInteger(artifact.bytes) ||
        artifact.bytes < 1 || artifact.bytes > Math.min(host.limits.maxInputBytes, 200000)) fail('candidate_bridge_artifact');
    const bytes = pinned({path: host.candidatePath, sha256: artifact.sha256}, Math.min(host.limits.maxInputBytes, 200000));
    if (bytes.length !== artifact.bytes) fail('candidate_bridge_artifact');
    receipt.candidateSha256 = hash(bytes); receipt.candidateBytes = bytes.length;
  };
  const identity = exec => {
    receipt.registrationChecks++;
    const definition = owned.get(exec.name);
    const valid = definition !== undefined && ctx.tools.get(exec.name, exec.agent) === definition;
    if (!valid && definition !== undefined) receipt.identityFailures++;
    return valid;
  };
  // Native delivery has two independent witnesses: the runtime's frozen final
  // outcome and the same result appended by AgentLoop to its live Session.
  // No session log is searched, and no assistant text is manufactured.
  const callsBySession = new WeakMap();
  const nativeCalls = new WeakMap();
  const staged = new WeakMap();
  let activeNativeExecution;
  let pendingConclusion;
  let terminalClosed = false;
  const validId = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$/.test(value);
  const committedEvent = (session, event) => sessionRuntime !== undefined &&
    session instanceof sessionRuntime.Session && ctx.get('sessions')?.get(session.id) === session &&
    Number.isSafeInteger(event?.seq) && event.seq >= 0 &&
    sessionRuntime.Session.prototype.eventAt.call(session, event.seq) === event;
  const validCall = event => event?.type === 'tool/call' && object(event.data) &&
    validId(event.data.callId) && TOOL_NAMES.includes(event.data.name) &&
    Number.isSafeInteger(event.data.turn) && event.data.turn > 0 &&
    Number.isSafeInteger(event.data.step) && event.data.step > 0 && typeof event.data.arguments === 'string';
  const invalidateConclusion = () => {
    pendingConclusion = undefined;
    receipt.nativeToolConclusion = null;
    receipt.candidateSha256 = null; receipt.candidateBytes = 0;
    save();
  };
  if (sessionRuntime) {
    ctx.on('tools/result', (exec, result) => {
      if (activeNativeExecution === exec) activeNativeExecution = undefined;
      const entry = staged.get(exec);
      if (entry === undefined) return;
      staged.delete(exec);
      if (result.isError !== false || result.concludesTurn !== true || exec.signal.aborted ||
          !Object.isFrozen(exec) || !Object.isFrozen(result) ||
          ctx.tools.get(exec.name, exec.agent) !== owned.get(exec.name) ||
          !isDeepStrictEqual(result.value, entry.value) ||
          !isDeepStrictEqual(result.content, [{type: 'text', text: JSON.stringify(entry.value)}])) {
        invalidateConclusion(); return;
      }
      // tools/result observers are synchronous and are not awaited. Close
      // admission here, before AgentLoop can dispatch a same-batch tail call.
      terminalClosed = true;
      pendingConclusion = {...entry, result};
    });
    ctx.on('session/event', (session, event) => {
      if (!committedEvent(session, event)) return;
      if (validCall(event)) {
        if (terminalClosed) return;
        let calls = callsBySession.get(session);
        if (calls === undefined) { calls = new Map(); callsBySession.set(session, calls); }
        // Only bounded, live correlation state is retained; consumed calls
        // are deleted by the guard, including repeated ids on later steps.
        if (calls.size < host.limits.maxToolCalls || calls.has(event.data.callId)) calls.set(event.data.callId, event);
        return;
      }
      const entry = pendingConclusion;
      if (entry === undefined || session !== entry.session || event.type !== 'tool/result') return;
      const data = event.data;
      const message = data?.message;
      if (message?.toolCallId !== entry.exec.callId) return;
      const callEvent = entry.callEvent;
      if (entry.exec.signal.aborted || receipt.allowedCount !== entry.allowedOrdinal ||
          !committedEvent(session, callEvent) || event.seq <= callEvent.seq || event.surfaceOp !== 'append' ||
          !isDeepStrictEqual(event.sourceEventSeqs, [callEvent.seq]) ||
          data.turn !== callEvent.data.turn || data.step !== callEvent.data.step ||
          message.role !== 'tool' || message.isError !== false ||
          !isDeepStrictEqual(message.source, {kind: 'tool', callId: entry.exec.callId}) ||
          !isDeepStrictEqual(message.content, entry.result.content) || data.error !== undefined ||
          !isDeepStrictEqual(data.meta, entry.result.meta)) {
        invalidateConclusion(); return;
      }
      try {
        confirmCandidate({sha256: entry.sha256, bytes: entry.bytes});
        receipt.nativeToolConclusion = {
          schema: 'lexicon.ielts.native-tool-conclusion.v1',
          sessionId: session.id, turnId: data.turn, stepId: data.step,
          callId: entry.exec.callId, callEventSeq: callEvent.seq, eventSeq: event.seq,
          toolName: entry.exec.name, allowedOrdinal: entry.allowedOrdinal,
          candidateSha256: entry.sha256, candidateBytes: entry.bytes,
          toolsResultObserved: true, sessionEventCommitted: true,
        };
        // This proves an in-memory Session commit. The host separately
        // requires normal headless completion, persistence flush and reaping.
        save(); pendingConclusion = undefined;
      } catch {
        invalidateConclusion();
      }
    });
  }
  // Registered before asynchronous initialization; even failed loading cannot
  // accidentally admit an unrestricted tool during this plugin's lifetime.
  ctx.tools.guard(exec => {
    if (terminalClosed) { receipt.deniedCount++; save(); return 'lexicon_ielts:terminal_closed'; }
    if (!receipt.pluginReady || !identity(exec) || exec.parent !== undefined) {
      receipt.deniedCount++; save(); return 'lexicon_ielts:tool_denied';
    }
    try { checkContinuation(host); }
    catch { receipt.deniedCount++; save(); return 'lexicon_ielts:continuation_changed'; }
    if (permits.has(exec)) return undefined;
    let nativeCall;
    if (sessionRuntime) {
      const session = exec.agent?.session;
      const calls = session && callsBySession.get(session);
      const callEvent = calls?.get(exec.callId);
      let bound = false;
      try {
        bound = activeNativeExecution === undefined && validId(session?.id) &&
          (host.continuation.sessionId === null || session.id === host.continuation.sessionId) &&
          committedEvent(session, callEvent) && validCall(callEvent) &&
          callEvent.data.name === exec.name && isDeepStrictEqual(JSON.parse(callEvent.data.arguments), exec.arguments);
      } catch { /* Unbound model arguments are rejected without exposing text. */ }
      if (!bound) { receipt.deniedCount++; save(); return 'lexicon_ielts:native_call_unbound'; }
      calls.delete(exec.callId);
      nativeCall = {session, callEvent};
    }
    if (receipt.carriedToolCalls + receipt.allowedCount >= host.limits.maxToolCalls) {
      receipt.deniedCount++; save(); return 'lexicon_ielts:tool_budget';
    }
    receipt.allowedCount++; receipt.perTool[exec.name]++; permits.add(exec);
    if (nativeCall) {
      activeNativeExecution = exec;
      nativeCalls.set(exec, {...nativeCall, allowedOrdinal: receipt.allowedCount});
    }
    // Invalidate before runtime schema checks or argument encoding can reject
    // a later validation attempt; its old digest must not remain confirmable.
    if (exec.name === 'lexicon_ielts_validate' && host.candidatePath) {
      receipt.candidateSha256 = null; receipt.candidateBytes = 0;
    }
    save();
    return undefined;
  });
  save();
  const startup = await bridge(host, 'ready', {}, undefined);
  if (!startup.ok) fail('initialization_rejected');
  if (!Number.isSafeInteger(startup.startupMetadataBytes) || startup.startupMetadataBytes < 1 ||
      startup.startupMetadataBytes > READY_OUTPUT_CAP) fail('initialization_size');
  receipt.startupMetadataBytes = startup.startupMetadataBytes;
  const ready = frozen(startup.value);
  if (host.files.candidateModule) {
    if (ready.candidate_compiler_ready !== true) fail('candidate_compiler_unavailable');
    receipt.candidateCompilerReady = true;
  }
  if (!Array.isArray(ready.document_ids) || ready.document_ids.length !== 17) fail('corpus_inventory');
  if (!Array.isArray(ready.article_document_ids) || ready.article_document_ids.length < 1 || ready.article_document_ids.some(id => typeof id !== 'string' || !/^article:[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(id))) fail('article_inventory');
  const documentIds = [...ready.document_ids, ...ready.article_document_ids];
  if (new Set(documentIds).size !== documentIds.length) fail('duplicate_document_id');
  const strictSchema = Object.hasOwn(host.files, 'schemaModule');
  const candidateSchema = strictSchema ? ready.candidate_schema : {type: 'object', additionalProperties: true};
  if (strictSchema) {
    if (!object(candidateSchema) || candidateSchema.type !== 'object' || candidateSchema.additionalProperties !== false) fail('candidate_schema_missing');
    try { schemaRuntime.assertSupportedJsonSchema(candidateSchema); }
    catch { fail('candidate_schema_unsupported'); }
    receipt.candidateSchemaStrict = true;
  }
  const specs = [
    ['retrieve', 'Search the frozen official-format IELTS reference corpus. No web or filesystem search.', schema({query: {type: 'string', maxLength: 240}, module: {type: 'string', enum: ready.modules}, question_type: {type: 'string', enum: ready.question_types}, limit: {type: 'integer', minimum: 1, maximum: 8}})],
    ['read', 'Read a frozen reference document, or an article paragraph via article:<paragraph_id>. For article paragraphs only, optional quote returns every exact literal UTF-16 source range (up to 8); use these ranges without counting characters yourself. No regex, URL or filesystem lookup.', schema({document_id: {type: 'string', enum: documentIds}, quote: {type: 'string', minLength: 1, maxLength: 2000, description: 'Exact original text from this article paragraph; 1–2000 UTF-16 units. Omit for the full paragraph. Not accepted with reference-corpus IDs.'}}, ['document_id'])],
    ['validate', (host.mode === 'review' ? 'Check the structure of your complete independent review, including writing_task2_review even when reading items fail. This tool never discloses answer agreement or the publication decision.' : host.mode === 'explanation_review' ? 'Check the structure and frozen identities of your complete explanation_reviews. Cover every displayed reading explanation, including failures or insufficient evidence. Do not revise the package or blind answers. A complete rejection is a valid audit; the host decides publication.' : 'Validate your complete practice candidate against the frozen host request and exact supplied source ranges. Semantic review remains required.') + (host.candidatePath ? ' Unique paragraph_id/quote locators are compiled to exact UTF-16 ranges before strict validation. The compiled candidate is saved by the tool. ' + (host.candidateDelivery === NATIVE_DELIVERY ? 'A successful validation concludes this native turn. Make validation your final tool call; no later tool or assistant confirmation is required.' : 'Return only {"candidate_sha256":"<returned digest>"} as your final response; do not repeat the candidate.') : ''), schema({candidate: candidateSchema}, ['candidate'])],
  ];
  for (const [operation, description, parameters] of specs) {
    const toolName = `lexicon_ielts_${operation}`;
    const definition = frozen({
      name: toolName, description, parameters, timeoutMs: host.limits.timeoutMs + 500,
      isConcurrencySafe: () => false,
      output: {schema: {type: 'object', additionalProperties: true}, render: (_args, value) => [{type: 'text', text: JSON.stringify(value)}]},
      async execute(args, exec) {
        if (!permits.has(exec) || !identity(exec)) { receipt.deniedCount++; save(); fail('unapproved_dispatch'); }
        try {
          const encoded = JSON.stringify(args);
          if (Buffer.byteLength(encoded) > host.limits.maxInputBytes) fail('input_bound');
          const checked = JSON.parse(encoded);
          validateArgs(operation, checked, ready);
          if (operation === 'validate' && strictSchema && schemaRuntime.validateJsonSchemaValue(candidateSchema, checked.candidate, 'candidate').length > 0) fail('candidate_schema');
          const result = await bridge(host, operation, checked, exec.signal);
          if (result.ok) {
            if (operation === 'validate' && host.candidatePath) {
              if (exec.signal?.aborted) fail('cancelled');
              confirmCandidate(result.artifact);
              result.value = {...result.value, candidate_sha256: receipt.candidateSha256};
              delete result.artifact;
            }
            receipt.completedCount++; receipt.completedPerTool[toolName]++;
          } else receipt.failedCount++;
          save();
          if (result.ok && operation === 'validate' && sessionRuntime) {
            const binding = nativeCalls.get(exec);
            if (binding === undefined || exec.signal.aborted) fail('native_call_unbound');
            staged.set(exec, {...binding, exec, value: result.value,
              sha256: receipt.candidateSha256, bytes: receipt.candidateBytes});
            exec.concludeTurn();
          }
          return result.ok ? result.value : result;
        } catch (error) {
          staged.delete(exec);
          receipt.failedCount++; save();
          const code = /^lexicon_ielts:([a-z_]{1,64})$/.exec(error.message ?? '')?.[1] ?? 'tool_failure';
          throw new Error(`lexicon_ielts:${code}`);
        }
      },
    });
    ctx.tools.register(definition); owned.set(toolName, definition);
  }
  receipt.registrationIdentityVerified = TOOL_NAMES.every(tool => ctx.tools.get(tool) === owned.get(tool));
  if (!receipt.registrationIdentityVerified) fail('registration_identity');
  receipt.pluginReady = true; save();
  ctx.provide(READY_SERVICE, Object.freeze({ready: true, manifestSha256: host.manifestSha256, mode: host.mode, strictSchemaReady: receipt.candidateSchemaStrict, compilerReady: receipt.candidateCompilerReady}));
}

// The full fixed rc.5 headless bundle is reduced by row ID. JSON is valid YAML,
// so no evaluator/interpolation is needed for the additional host settings.
const DISABLED_ROWS = Object.freeze(`tool-plugin-manager plugin-manager timer hmr deepseek-llm-api-extensions session-log-deepseek typert typert-loader typert-gateway remote-access session-title session-title-llm user-questions plugin-package-inventory-deepseek jobs llm-retry config-editor settings authorization deepseek-account credentials llm-pi-ai attachment-local session-query-sqlite working-directory tool-working-directory storage storage-json storage-domain session-projection-cache otel session-telemetry-otel subprocess sandbox sandbox-policy bash-sandbox pwsh-sandbox approval permission shell-env tool-bash tool-pwsh tool-jobs fs-observation-policy tool-fs tool-fs-search agent-instructions skill skill-filesystem tool-skill commands command-feedback goal goal-round-driver command-goal plan-mode token-meter compaction-basic command-compact subagent subagent-spawn-in-process subagent-fork-in-process tool-subagent-control tool-subagent-list-agents tool-subagent tool-subagent-fork ptc-runtime workflow-ptc tool-workflow timeout-policy spill-local spill-policy session-checkpoint-policy tool-result-pruner image-offload tool-todo tool-goal repeat-tool-reminder web web-search-deepseek web-deepseek-api web-fetch-http tool-web mcp-resources fs-sandbox llm-deepseek-account`.split(' '));
export const ACTIVE_HEADLESS_ROWS = Object.freeze(['llm', 'session', 'agent', 'agent-default-model', 'session-persistence-jsonl', 'session-projection', 'tools', 'system-prompt', 'agent-loop', 'llm-deepseek', 'headless-startup', 'headless-runner', 'lexicon-ielts', 'lexicon-session-query']);
export function buildHeadlessPatch({manifestPath, manifestSha256, modelBaseURL, tokenEnv = 'LEXICON_DUMMY_KEY', model = 'deepseek-flash', maxTokens, streamIdleTimeoutMs, reasoningEffort = 'off', candidateDelivery = LEGACY_DELIVERY}) {
  absolute(manifestPath);
  if (!DELIVERY_MODES.includes(candidateDelivery)) fail('invalid_candidate_delivery');
  const expectedTokens = reasoningEffort === 'off' ? 8192 : 16384;
  const expectedIdle = reasoningEffort === 'off' ? 100000 : 190000;
  if (maxTokens === undefined) maxTokens = expectedTokens;
  if (streamIdleTimeoutMs === undefined) streamIdleTimeoutMs = expectedIdle;
  if (!SHA.test(manifestSha256) || !/^[A-Z][A-Z0-9_]{0,63}$/.test(tokenEnv) || !['deepseek-flash', 'deepseek-v4-pro'].includes(model) || maxTokens !== expectedTokens || streamIdleTimeoutMs !== expectedIdle || !['off', 'low', 'high', 'max'].includes(reasoningEffort)) fail('invalid_profile');
  const url = new URL(modelBaseURL);
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || !url.port || !['/', '/v1', '/v1/'].includes(url.pathname) || url.username || url.password || url.search || url.hash) fail('loopback_required');
  return JSON.stringify([
    ...DISABLED_ROWS.map(id => ({id, disabled: true})),
    {id: 'tools', config: {mode: 'native', maxParallelSubCalls: 1}},
    {id: 'agent-loop', config: {agents: [], maxParallelToolCalls: 1}},
    {id: 'system-prompt', config: {personaPrefix: 'You produce evidence-bound IELTS practice. Use only the three provided tools and supplied passage. Generation must successfully call lexicon_ielts_retrieve, lexicon_ielts_read and lexicon_ielts_validate. Answer-blind and explanation reviews already receive the complete frozen passage and their output contract: review that supplied text directly, and use retrieve/read only when a format rule or an ambiguous quote needs clarification. Every review must successfully call lexicon_ielts_validate; do not repeat retrieval to satisfy a tool count, or claim a retrieval you did not perform. Examine all relevant supplied paragraphs even when no read tool call is needed. An answer-blind review must include every requested reading review AND a complete writing_task2_review even when rejecting reading questions. The separate explanation review contains every requested explanation_reviews item and does not add writing_task2_review. Follow the stage-specific OUTPUT CONTRACT. It describes the compiled artifact. For the candidate argument of lexicon_ielts_validate, prefer paragraph_id/quote locators when the exact quote is unique within its paragraph. The tool resolves them and can derive complete-token answer ranges from unambiguous supplied contexts. Existing full ranges are checked without repair. Submit the complete candidate and correct every structure error. ' + (candidateDelivery === NATIVE_DELIVERY ? 'Successful lexicon_ielts_validate saves the compiled candidate and concludes the native turn. It must be your final tool call. Do not request later tools in the same batch, repeat the candidate or add an assistant confirmation.' : 'When it returns candidate_sha256, the tool has saved the compiled candidate: finish with exactly {"candidate_sha256":"<returned digest>"}, without repeating candidate JSON, commentary or Markdown.') + ' Tool validation does not disclose semantic agreement; a failed item must still be reviewed honestly and must not skip required items.'}},
    {id: 'agent-default-model', config: {provider: 'deepseek-official', model, reasoningEffort}},
    {id: 'llm-deepseek', config: {baseURL: modelBaseURL, apiKeyEnv: tokenEnv, reasoningEffort, maxTokens, streamIdleTimeoutMs, retryPolicy: {mode: 'normal', maxRetries: 0}, models: [{id: model, inputModalities: ['text'], contextWindow: 1048576, maxTokens}]}},
    {insert: [
      {id: 'lexicon-session-query', name: '@deepseek-ai/dsh-session-query', config: {persistedReadConcurrency: 1, preparedSessionCacheSize: 1}},
      {id: 'lexicon-ielts', name: fileURLToPath(import.meta.url), config: {manifestPath, manifestSha256}},
    ]},
    {id: 'headless-runner', inject: ['headlessStartup', READY_SERVICE]},
  ], null, 2) + '\n';
}
