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
