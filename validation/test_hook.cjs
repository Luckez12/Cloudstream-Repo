const fs=require('fs'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync('MSM21/src/main/kotlin/com/msm21/extractors.kt','utf8');
const hook=source.split('private const val HOOK_JS = """')[1].split('"""')[0].replace(/<\/?script>/g,'').replace('__MSM_BLOCKED_HOSTS__','["mc.yandex.ru","pixel.morphify.com"]');
function run({body='',heading='',videos=[],sources=[]}={}){
 const out=[];const context={URL,TextDecoder,window:{msmBridge:{capture:v=>out.push(v)},jwplayer:()=>({getPlaylist:()=>[{sources}]})},document:{baseURI:'https://abyss.msmbot.club/',body:{innerText:body},querySelector:q=>q==='h1, h2'&&heading?{innerText:heading}:null,querySelectorAll:q=>q==='video'?videos:[]},setTimeout:()=>{},setInterval:()=>{}};
 vm.runInNewContext(hook,context);return out;
}
assert(run({body:'404 Page not found',heading:'Page not found'}).includes('MSM_PAGE_STATE|not_found'));
assert(run({body:'Verify you are human'}).includes('MSM_VERIFY|human_check'));
const virtual='https://storage.googleapis.com/mediastorage/x/y/539709300.mp4#mp4/r2/1/539709300/270532608/480p/h264?maxChunkSize=5242880';
assert(run({videos:[{currentSrc:virtual}]}).includes('MSM_VIDEO|'+virtual));
const captures=run({sources:[{file:'https://example.com/master.m3u8',type:'application/x-mpegurl'},{file:'https://mc.yandex.ru/watch/x?video=movie.mp4'},{file:'blob:bad'}]});
assert(captures.some(v=>v.includes('master.m3u8')));assert(!captures.some(v=>v.includes('yandex')||v.includes('blob:')));
assert(run({body:'FAQ: page not found',sources:[{file:'https://example.com/video.mp4'}]}).some(v=>v.includes('video.mp4')));
console.log('PASS: Byse not-found, human verification, Abyss virtual capture, HLS preservation, tracker/blob rejection, non-error FAQ');
