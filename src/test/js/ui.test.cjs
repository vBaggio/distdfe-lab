const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');
const html = fs.readFileSync('src/main/resources/static/index.html', 'utf8');
const script = fs.readFileSync('src/main/resources/static/app.js', 'utf8');
const baseState = {
  ultNSU: '000000000000005', maxNSU: '000000000000005', ultimaConsulta: null,
  proximaConsultaPermitida: null, agora: '2026-09-26T12:00:00Z', emAndamento: false,
  podeConsultar: true, configurado: true, ambiente: 1, pendencia: '', mensagem: 'Pronto', ultimasExecucoes: []
};
function setup(state = baseState, data = {total: 0, documentos: [], totaisPorSchema: {}}) {
  const dom = new JSDOM(html, {runScripts: 'outside-only', url: 'http://localhost:8080'});
  const calls = [];
  let nextPoll;
  dom.window.setTimeout = f => { nextPoll = f; };
  dom.window.fetch = async (path, options) => {
    calls.push({path, options});
    if (options?.method === 'POST') return {ok: true, json: async () => ({mensagem: 'Aceita'})};
    return {ok: true, json: async () => path.endsWith('estado') ? state : data};
  };
  dom.window.eval(script);
  return {dom, calls, poll: () => nextPoll(), $: id => dom.window.document.getElementById(id)};
}
const settle = () => new Promise(resolve => setImmediate(resolve));
test('sem certificado mantém consulta desabilitada e nunca dispara POST no carregamento', async () => {
  const ui = setup({...baseState, configurado:false, podeConsultar:false, pendencia:'Configure o certificado'});
  try {
    await settle(); assert.equal(ui.$('consultar').disabled,true); assert.equal(ui.$('validar').disabled,true);
    assert.equal(ui.$('pendencia').hidden,false); assert.match(ui.$('status').textContent,/CONFIGURAÇÃO/);
    assert.equal(ui.calls.some(c=>c.options?.method==='POST'),false);
  } finally {ui.dom.window.close();}
});
test('botão manual envia um POST JSON e bloqueio do servidor desabilita consulta', async () => {
  const state={...baseState}; const ui=setup(state);
  try {
    await settle(); assert.equal(ui.$('consultar').disabled,false);
    ui.$('consultar').click(); state.podeConsultar=false; state.ultimaConsulta=state.agora;
    state.proximaConsultaPermitida='2026-09-26T13:00:00Z'; await settle(); await ui.poll();
    const posts=ui.calls.filter(c=>c.options?.method==='POST'); assert.equal(posts.length,1);
    assert.equal(posts[0].path,'/api/consultar'); assert.equal(posts[0].options.headers['Content-Type'],'application/json');
    assert.equal(ui.$('consultar').disabled,true); assert.match(ui.$('countdown').textContent,/60 min/);
  } finally {ui.dom.window.close();}
});
test('renderiza e filtra dados sem interpretar HTML de emitentes ou histórico', async () => {
  const dangerous='<img src=x onerror="window.attacked=true">';
  const state={...baseState, ultimasExecucoes:[{horario:baseState.agora,nsuEnviado:'000000000000000',cStat:'138',xMotivo:dangerous,quantidadeDocs:1,ultNSU:baseState.ultNSU}]};
  const data={total:1,totaisPorSchema:{'resNFe_v1.01.xsd':1},documentos:[{nsu:baseState.ultNSU,chave:'1'.repeat(44),emitenteNome:dangerous,emitenteDocumento:'',schema:'resNFe_v1.01.xsd',tamanhoBytes:300,vNF:'123.45',dhEmi:baseState.agora,cSitNFe:'1'}]};
  const ui=setup(state,data);
  try {
    await settle(); assert.equal(ui.dom.window.document.querySelectorAll('img').length,0);
    assert.ok(ui.$('documentos').textContent.includes(dangerous)); assert.equal(ui.$('total').textContent,'1');
    ui.$('busca').value='não existe';ui.$('busca').dispatchEvent(new ui.dom.window.Event('input'));
    assert.match(ui.$('documentos').textContent,/Nenhum documento corresponde/);
    ui.$('busca').value='1111';ui.$('busca').dispatchEvent(new ui.dom.window.Event('input'));
    assert.ok(ui.$('documentos').textContent.includes(dangerous));
  } finally {ui.dom.window.close();}
});
test('validar PFX usa endpoint local e perda de conexão desabilita os botões', async () => {
  const ui=setup();
  try {
    await settle();ui.$('validar').click();await settle();
    assert.equal(ui.calls.filter(c=>c.options?.method==='POST')[0].path,'/api/certificado/validar');
    ui.dom.window.fetch=async()=>{throw new Error('offline');};await ui.poll();
    assert.equal(ui.$('consultar').disabled,true);assert.equal(ui.$('validar').disabled,true);
    assert.equal(ui.$('status').textContent,'SEM CONEXÃO');
  } finally {ui.dom.window.close();}
});
