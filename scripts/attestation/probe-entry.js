import {JsAnalyzer, JsExtractor, JsMatchers, Utils} from './youtube-player.js';
import {BotGuardClient} from './bgutils/dist/core/BotGuardClient.js';
import {parseChallengeData} from './bgutils/dist/core/ChallengeFetcher.js';
import {WebPoMinter} from './bgutils/dist/core/WebPoMinter.js';
import {getHeaders} from './bgutils/dist/utils/helpers.js';
let state={stage:'idle'},signals=[],minter;
async function challenge(raw){
 try{
  if(location.origin!=='https://appassets.androidplatform.net')throw new Error('ORIGIN');
  const c=parseChallengeData(raw);
  const script=c.interpreterJavascript?.privateDoNotAccessOrElseSafeScriptWrappedValue;
  if(typeof script!=='string'||script.length>2000000)throw new Error('INTERPRETER_UNAVAILABLE');
  state={stage:'runtime'};
  new Function(script)();
  const bg=await BotGuardClient.create({program:c.program,globalName:c.globalName,globalObject:globalThis});
  const snapshot=await bg.snapshot({webPoSignalOutput:signals},10000);
  state={stage:'snapshot_ready',snapshot};
 }catch(e){state={stage:'failed',code:e.name};}
}
async function integrity(raw,id){
 try{
  if(!raw[0]||!Number.isFinite(raw[1])||raw[1]<=0)throw new Error('INTEGRITY_REJECTED');
  minter=await WebPoMinter.create({integrityToken:raw[0]},signals);
  const token=await minter.mintAsWebsafeString(id);
  state={stage:'ready',token,videoId:id};
 }catch(e){state={stage:'failed',code:e.name};}
}
Object.defineProperty(globalThis,'KasaneAttest',{value:Object.freeze({challenge,integrity,
 mint:async(binding)=>{try{state={stage:'minting'};const token=await minter.mintAsWebsafeString(binding);state={stage:'ready',token};}catch(e){state={stage:'failed',code:e.name}}},
 headers:()=>{const h=getHeaders();delete h['user-agent'];return JSON.stringify(h)},
 status:()=>JSON.stringify(state)})});

// Uses the licensed library's extractor and processor; no guessed transform.
Object.defineProperty(globalThis,'KasanePlayer',{value:Object.freeze({prepare:(source,n)=>{
 try {
  if(location.origin!=='https://appassets.androidplatform.net'||typeof source!=='string'||source.length>4000000||!/^[-_a-zA-Z0-9]{1,4096}$/.test(n))throw new Error('INPUT');
  const analyzer=new JsAnalyzer(source,{extractions:[{friendlyName:'nsigFunction',match:JsMatchers.nsigMatcher}]});
  const extracted=new JsExtractor(analyzer).buildScript({disallowSideEffectInitializers:true});
  if(!extracted.exported.includes('nsigFunction'))throw new Error('PLAYER_UNSUPPORTED');
  const result=new Function(extracted.output+'\n'+Utils.getNsigProcessorFn(n))();
  if(typeof result?.n!=='string'||!/^[-_a-zA-Z0-9]{1,4096}$/.test(result.n)||result.n===n||result.n.startsWith('enhanced_except_'))throw new Error('PLAYER_RESULT');
  return JSON.stringify({n:result.n});
 }catch(error){return JSON.stringify({error:'PLAYER_PREPARATION_FAILED'});}
}})});
