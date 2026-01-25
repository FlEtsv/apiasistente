/**
 * AI Assistant JS - Versión Avanzada Corregida
 */
const chatEl = document.getElementById('chat');
const inputEl = document.getElementById('input');
const sendBtn = document.getElementById('send');
const sidEl = document.getElementById('sid');

let sessionId = localStorage.getItem('apiasistente.sessionId');

function scrollDown() {
    // Usamos requestAnimationFrame para asegurar que el DOM se haya renderizado
    requestAnimationFrame(() => {
        chatEl.scrollTo({
            top: chatEl.scrollHeight,
            behavior: 'smooth'
        });
    });
}

/**
 * Añade un mensaje a la interfaz con estilos Tailwind
 */
function addMsg(who, text, sources = []) {
    // Limpiar el mensaje de bienvenida si existe
    if (chatEl.querySelector('.opacity-50')) chatEl.innerHTML = '';

    const wrapper = document.createElement('div');
    wrapper.className = `flex ${who === 'user' ? 'justify-end' : 'justify-start'} animate-msg mb-6`;

    const div = document.createElement('div');
    const isUser = who === 'user';

    // Clases de diseño según el autor
    const baseClasses = "max-w-[85%] md:max-w-[75%] px-5 py-3 rounded-2xl shadow-xl text-sm leading-relaxed transition-all";
    const userClasses = "bg-blue-600 text-white rounded-tr-none ml-12";
    const aiClasses = "glass text-slate-100 rounded-tl-none mr-12 border border-slate-700/50";

    div.className = `${baseClasses} ${isUser ? userClasses : aiClasses}`;

    // Contenido del mensaje (soporta saltos de línea)
    const content = document.createElement('div');
    content.innerHTML = text.replace(/\n/g, '<br>');
    div.appendChild(content);

    // Fuentes RAG si existen
    if (sources && sources.length > 0) {
        const sourcesDiv = document.createElement('div');
        sourcesDiv.className = "mt-4 pt-3 border-t border-slate-700/50 space-y-2";

        const title = document.createElement('p');
        title.className = "text-[10px] uppercase tracking-widest text-slate-400 font-bold mb-2";
        title.textContent = "Fuentes Consultadas";
        sourcesDiv.appendChild(title);

        sources.forEach((s, idx) => {
            const sourceCard = document.createElement('div');
            sourceCard.className = "text-[11px] bg-slate-800/50 p-2 rounded-lg border border-slate-700/30 hover:border-blue-500/30 transition-colors";
            sourceCard.innerHTML = `
                <div class="flex justify-between font-bold text-blue-400 mb-1">
                    <span>${s.documentTitle || 'Documento ' + (idx+1)}</span>
                    <span class="opacity-60 text-[9px]">Relevancia: ${(s.score * 100).toFixed(0)}%</span>
                </div>
                <div class="text-slate-400 italic font-light">"${s.snippet}"</div>
            `;
            sourcesDiv.appendChild(sourceCard);
        });
        div.appendChild(sourcesDiv);
    }

    wrapper.appendChild(div);
    chatEl.appendChild(wrapper);
    scrollDown();
}

/**
 * Muestra una animación de carga mientras la IA responde
 */
function showTyping() {
    const id = 'typing-' + Date.now();
    const html = `
        <div id="${id}" class="flex justify-start animate-msg mb-6 animate-pulse-slow">
            <div class="glass px-5 py-4 rounded-2xl rounded-tl-none mr-12 border border-slate-700/50">
                <div class="flex gap-1.5">
                    <div class="w-2 h-2 bg-blue-500 rounded-full animate-bounce"></div>
                    <div class="w-2 h-2 bg-blue-500 rounded-full animate-bounce [animation-delay:-0.3s]"></div>
                    <div class="w-2 h-2 bg-blue-500 rounded-full animate-bounce [animation-delay:-0.5s]"></div>
                </div>
            </div>
        </div>
    `;
    chatEl.insertAdjacentHTML('beforeend', html);
    scrollDown();
    return id;
}

async function loadHistory() {
    if (!sessionId) return;
    sidEl.textContent = sessionId.substring(0, 8);

    try {
        const res = await fetch(`/api/chat/${sessionId}/history`);
        if (!res.ok) return;
        const msgs = await res.json();

        if (msgs.length > 0) chatEl.innerHTML = '';
        msgs.forEach(m => {
            addMsg(m.role === 'USER' ? 'user' : 'assistant', m.content);
        });
    } catch (e) { console.error("Error cargando historial", e); }
}

async function send() {
    const text = inputEl.value.trim();
    if (!text) return;

    inputEl.value = '';
    addMsg('user', text);

    const typingId = showTyping();
    sendBtn.disabled = true;

    try {
        const res = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sessionId, message: text })
        });

        const typingEl = document.getElementById(typingId);
        if (typingEl) typingEl.remove();

        if (!res.ok) throw new Error(await res.text());

        const data = await res.json();
        sessionId = data.sessionId;
        localStorage.setItem('apiasistente.sessionId', sessionId);
        sidEl.textContent = sessionId.substring(0, 8);

        addMsg('assistant', data.reply, data.sources);
    } catch (e) {
        const typingEl = document.getElementById(typingId);
        if (typingEl) typingEl.remove();
        addMsg('assistant', '⚠️ Error: ' + e.message);
    } finally {
        sendBtn.disabled = false;
        inputEl.focus();
    }
}

// Listeners
sendBtn.addEventListener('click', send);
inputEl.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') send();
});

loadHistory();