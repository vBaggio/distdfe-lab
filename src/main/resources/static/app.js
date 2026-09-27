'use strict';
const $ = id => document.getElementById(id);
let state = null, documents = [], pending = false;
const date = value => value ? new Date(value).toLocaleString('pt-BR') : '—';
const text = (id, value) => { $(id).textContent = value; };
function cell(row, value, className = '') {
  const td = document.createElement('td'); td.textContent = value || '—';
  td.className = className; row.append(td); return td;
}
function empty(tbody, columns, message) {
  const row = document.createElement('tr'); const td = cell(row, message, 'empty');
  td.colSpan = columns; tbody.append(row);
}
function renderState(s) {
  state = s;
  text('ambiente', s.ambiente === 1 ? 'PRODUÇÃO' : 'HOMOLOGAÇÃO');
  text('ultNSU', s.ultNSU); text('maxNSU', s.maxNSU);
  text('ultimaConsulta', date(s.ultimaConsulta)); text('proximaConsulta', date(s.proximaConsultaPermitida));
  text('mensagem', s.mensagem); text('pendencia', s.pendencia); $('pendencia').hidden = !s.pendencia;
  text('status', s.emAndamento ? 'EM ANDAMENTO' : !s.configurado ? 'CONFIGURAÇÃO PENDENTE' : s.podeConsultar ? 'DISPONÍVEL' : 'AGUARDANDO INTERVALO');
  $('consultar').disabled = pending || !s.podeConsultar;
  $('validar').disabled = pending || s.emAndamento || !s.configurado;
  const remaining = s.proximaConsultaPermitida ? Math.max(0, new Date(s.proximaConsultaPermitida) - new Date(s.agora)) : 0;
  text('countdown', !s.podeConsultar && s.configurado && !s.emAndamento ? `Aguarde ${Math.floor(remaining / 60000)} min ${Math.floor(remaining % 60000 / 1000)} s e a liberação pelo servidor.` : '');
  const tbody = $('historico'); tbody.replaceChildren();
  for (const c of s.ultimasExecucoes) {
    const row = document.createElement('tr'); cell(row, date(c.horario)); cell(row, c.nsuEnviado, 'mono');
    cell(row, c.cStat || 'ERRO'); cell(row, c.erro || c.xMotivo); cell(row, String(c.quantidadeDocs), 'number'); cell(row, c.ultNSU, 'mono'); tbody.append(row);
  }
  if (!s.ultimasExecucoes.length) empty(tbody, 6, 'Nenhuma consulta realizada.');
}
function renderDocuments() {
  const query = $('busca').value.trim().toLocaleLowerCase('pt-BR');
  const tbody = $('documentos'); tbody.replaceChildren();
  const filtered = documents.filter(d => `${d.chave} ${d.emitenteNome} ${d.emitenteDocumento} ${d.nsu}`.toLocaleLowerCase('pt-BR').includes(query));
  for (const d of filtered) {
    const row = document.createElement('tr'); const key = cell(row, d.chave, 'key');
    const detail = document.createElement('span'); detail.className = 'detail'; detail.textContent = `NSU ${d.nsu}`; key.append(detail);
    cell(row, d.emitenteNome || d.emitenteDocumento); cell(row, date(d.dhEmi));
    cell(row, d.vNF ? Number(d.vNF).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' }) : '', 'number');
    cell(row, d.xEvento || d.tpEvento || ({ '1': 'Autorizada', '2': 'Denegada', '3': 'Cancelada' }[d.cSitNFe] || d.cSitNFe));
    cell(row, d.schema); cell(row, String(d.tamanhoBytes), 'number'); tbody.append(row);
  }
  if (!filtered.length) empty(tbody, 7, query ? 'Nenhum documento corresponde ao filtro.' : 'Os documentos recebidos aparecerão aqui.');
}
async function request(path, method = 'GET') {
  const response = await fetch(path, method === 'POST' ? { method, headers: { 'Content-Type': 'application/json' }, body: '{}' } : { cache: 'no-store' });
  let body;
  try { body = await response.json(); } catch { throw new Error('Resposta inválida da aplicação.'); }
  if (!response.ok) throw new Error(body.mensagem || `Falha HTTP ${response.status}.`);
  return body;
}
async function refresh() {
  const [s, data] = await Promise.all([request('/api/estado'), request('/api/documentos')]);
  renderState(s); documents = data.documentos; text('total', String(data.total));
  $('schemas').replaceChildren();
  for (const [schema, count] of Object.entries(data.totaisPorSchema)) {
    const badge = document.createElement('span'); badge.className = 'badge'; badge.textContent = `${schema} · ${count}`; $('schemas').append(badge);
  }
  renderDocuments();
}
async function action(path) {
  pending = true; $('consultar').disabled = true; $('validar').disabled = true;
  try { const result = await request(path, 'POST'); await refresh(); text('mensagem', result.mensagem); }
  catch (e) { text('mensagem', e.message); }
  finally { pending = false; }
}
async function refreshCert() {
  const c = await request('/api/configuracao');
  text('cert-status', c.configurado ? 'CONFIGURADO' : 'PENDENTE');
  text('cert-resumo', c.configurado ? `${c.titular || 'Empresa'} · CNPJ ${c.cnpj} · UF ${c.uf} · origem: ${c.origem}` : 'Selecione o certificado, digite a senha e a UF. É só uma vez.');
  $('cert-form').hidden = c.configurado; $('cert-trocar').hidden = !c.configurado || c.origem !== 'tela';
}
$('cert-trocar').addEventListener('click', () => { $('cert-form').hidden = false; $('cert-trocar').hidden = true; });
$('cert-form').addEventListener('submit', async ev => {
  ev.preventDefault(); text('cert-msg', 'Salvando…');
  try {
    const response = await fetch('/api/configuracao', { method: 'POST', body: new FormData($('cert-form')) });
    const body = await response.json();
    if (!response.ok) throw new Error(body.mensagem || `Falha HTTP ${response.status}.`);
    $('cert-senha').value = ''; text('cert-msg', ''); await refreshCert(); await refresh();
  } catch (e) { text('cert-msg', e.message); }
});
refreshCert().catch(() => text('cert-resumo', 'Não foi possível ler a configuração.'));
$('consultar').addEventListener('click', () => action('/api/consultar'));
$('validar').addEventListener('click', () => action('/api/certificado/validar'));
$('busca').addEventListener('input', renderDocuments);
async function poll() {
  if (!pending) {
    try { await refresh(); }
    catch { text('status', 'SEM CONEXÃO'); text('mensagem', 'Não foi possível acessar a aplicação. Verifique se ela está em execução.'); $('consultar').disabled = true; $('validar').disabled = true; }
  }
  setTimeout(poll, 2000);
}
poll();
