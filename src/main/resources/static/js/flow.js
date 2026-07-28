'use strict';

function _flowVar(name) {
    return getComputedStyle(document.body).getPropertyValue(name).trim() || '#888';
}

function _flowFmt8(val) {
    if (val == null) return '–';
    return Number(val).toLocaleString('de-DE', { minimumFractionDigits: 8, maximumFractionDigits: 8 });
}

function _flowNodeLabel(node) {
    switch (node.kind) {
        case 'POSITION':     return node.positionLabel;
        case 'BUY':          return t('flow.node.buy', { label: node.positionLabel });
        case 'SELL':         return t('flow.node.sell', { label: node.positionLabel });
        case 'EXTERNAL_IN':  return t('flow.node.externalIn', { label: node.positionLabel });
        case 'EXTERNAL_OUT': return t('flow.node.externalOut', { label: node.positionLabel });
        default:              return node.id;
    }
}

let _flowGraphCache = null;

async function initFlow() {
    await _loadFlowPositionOptions();
    await loadFlowGraph();
}

async function _loadFlowPositionOptions() {
    const sel = document.getElementById('flowFilterPosition');
    if (!sel) return;
    try {
        const data = await fetch('/api/btc-tracking/positions').then(r => r.json());
        sel.innerHTML = `<option value="">${t('flow.filter.position.all')}</option>` +
            data.map(p => `<option value="${p.id}">${esc(p.label)}</option>`).join('');
    } catch (err) {
        console.warn('positions load failed', err.message);
    }
}

function _buildFlowQuery() {
    const from = document.getElementById('flowFilterFrom')?.value;
    const to   = document.getElementById('flowFilterTo')?.value;
    const positionId = document.getElementById('flowFilterPosition')?.value;

    const params = new URLSearchParams();
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    if (positionId) params.set('positionId', positionId);
    return params.toString();
}

function applyFlowFilter() {
    loadFlowGraph();
}

function resetFlowFilter() {
    document.getElementById('flowFilterFrom').value = '';
    document.getElementById('flowFilterTo').value = '';
    document.getElementById('flowFilterPosition').value = '';
    loadFlowGraph();
}

async function loadFlowGraph() {
    const loading = document.getElementById('flowLoading');
    const emptyEl = document.getElementById('flowEmpty');
    const svgEl   = document.getElementById('flowSvg');

    emptyEl.classList.add('d-none');
    svgEl.style.display = 'none';
    loading.classList.remove('d-none');
    loading.textContent = t('flow.loading');

    const query = _buildFlowQuery();
    let data;
    try {
        data = await fetch('/api/btc-tracking/flow' + (query ? '?' + query : '')).then(r => r.json());
    } catch (err) {
        loading.textContent = t('toast.error') + ': ' + err.message;
        return;
    }

    loading.classList.add('d-none');
    _flowGraphCache = data;

    if (!data.nodes?.length || !data.links?.length) {
        emptyEl.classList.remove('d-none');
        return;
    }

    renderSankey(data);
}

window.addEventListener('resize', () => {
    if (_flowGraphCache) renderSankey(_flowGraphCache);
});

let _flowResizeTimeout = null;

function renderSankey(data) {
    clearTimeout(_flowResizeTimeout);
    _flowResizeTimeout = setTimeout(() => _doRenderSankey(data), 50);
}

function _doRenderSankey(data) {
    const svgEl = document.getElementById('flowSvg');
    svgEl.style.display = 'block';

    const width  = svgEl.clientWidth || 1000;
    const height = svgEl.clientHeight || 560;

    const nodeIndex = new Map(data.nodes.map((n, i) => [n.id, i]));
    const links = data.links.map(l => ({
        source: nodeIndex.get(l.source),
        target: nodeIndex.get(l.target),
        value:  Math.max(Number(l.value), 0.00000001),
        raw: l
    }));

    const sankeyGen = d3.sankey()
        .nodeId((_, i) => i)
        .nodeWidth(14)
        .nodePadding(18)
        .extent([[1, 5], [width - 1, height - 5]]);

    const graph = sankeyGen({
        nodes: data.nodes.map(d => ({ ...d })),
        links: links
    });

    const svg = d3.select(svgEl).attr('viewBox', [0, 0, width, height]);
    svg.selectAll('*').remove();

    const tooltip = document.getElementById('flowTooltip');

    function colorForLink(link) {
        const srcNode = link.source;
        const tgtNode = link.target;
        if (srcNode.kind === 'BUY') return _flowVar('--pos');
        if (tgtNode.kind === 'SELL') return _flowVar('--neg');
        if (srcNode.kind === 'EXTERNAL_IN' || tgtNode.kind === 'EXTERNAL_OUT') return _flowVar('--text-muted');
        if (srcNode.kind === 'POSITION' && tgtNode.kind === 'POSITION') return _flowVar('--accent');
        return _flowVar('--text-muted');
    }

    svg.append('g')
        .attr('fill', 'none')
        .selectAll('path')
        .data(graph.links)
        .join('path')
        .attr('class', 'flow-link')
        .attr('d', d3.sankeyLinkHorizontal())
        .attr('stroke', colorForLink)
        .attr('stroke-width', d => Math.max(1, d.width))
        .on('mousemove', (event, d) => _showLinkTooltip(event, d, tooltip))
        .on('mouseleave', () => tooltip.style.display = 'none');

    const node = svg.append('g')
        .selectAll('g')
        .data(graph.nodes)
        .join('g')
        .attr('class', 'flow-node');

    node.append('rect')
        .attr('x', d => d.x0)
        .attr('y', d => d.y0)
        .attr('width', d => d.x1 - d.x0)
        .attr('height', d => Math.max(1, d.y1 - d.y0))
        .attr('fill', d => d.kind === 'POSITION' ? _flowVar('--accent') : _flowVar('--border'))
        .on('mousemove', (event, d) => _showNodeTooltip(event, d, tooltip))
        .on('mouseleave', () => tooltip.style.display = 'none');

    node.append('text')
        .attr('x', d => d.x0 < width / 2 ? d.x1 + 6 : d.x0 - 6)
        .attr('y', d => (d.y0 + d.y1) / 2)
        .attr('dy', '0.35em')
        .attr('text-anchor', d => d.x0 < width / 2 ? 'start' : 'end')
        .text(d => _flowNodeLabel(d));
}

function _showLinkTooltip(event, d, tooltip) {
    const raw = d.raw;
    const details = raw.details.slice()
        .sort((a, b) => a.date.localeCompare(b.date))
        .map(det => {
            const isClipped = det.originalQuantity != null
                && Number(det.originalQuantity) > Number(det.quantity) + 1e-9;
            const suffix = isClipped
                ? ` <span style="color:var(--text-muted)">(${t('flow.link.originalAmount', { AMOUNT: _flowFmt8(det.originalQuantity) })})</span>`
                : '';
            return `${det.date.substring(0, 10)} — ${_flowFmt8(det.quantity)} BTC${suffix}`;
        })
        .join('<br>');
    tooltip.innerHTML = `
        <div style="font-weight:600;margin-bottom:.3rem">${raw.month}</div>
        <div>${t('flow.link.total')}: ${_flowFmt8(raw.value)} BTC</div>
        <div style="margin-top:.3rem;color:var(--text-muted)">${t('flow.link.transactions', { COUNT: raw.details.length })}</div>
        <div style="margin-top:.2rem;max-height:150px;overflow-y:auto">${details}</div>`;
    _positionTooltip(event, tooltip);
}

function _showNodeTooltip(event, d, tooltip) {
    let html = `<div style="font-weight:600">${_flowNodeLabel(d)}</div>`;
    if (d.positionType) html += `<div style="color:var(--text-muted)">${d.positionType}</div>`;
    if (d.kind === 'POSITION' && d.currentBalance != null) {
        html += `<div style="margin-top:.3rem">${t('flow.node.balance')}: ${_flowFmt8(d.currentBalance)} BTC</div>`;
    }
    tooltip.innerHTML = html;
    _positionTooltip(event, tooltip);
}

function _positionTooltip(event, tooltip) {
    tooltip.style.display = 'block';
    tooltip.style.left = (event.clientX + 14) + 'px';
    tooltip.style.top  = (event.clientY + 14) + 'px';
}

function esc(str) {
    if (str == null) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/"/g, '&quot;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}