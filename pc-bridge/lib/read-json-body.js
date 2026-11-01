function readJsonBody(request, maxBytes = 32 * 1024 * 1024) {
    return new Promise((resolve, reject) => {
        const chunks = [];
        let bytes = 0;
        const cleanup = () => {
            request.removeListener('data', onData);
            request.removeListener('end', onEnd);
            request.removeListener('error', onError);
            request.removeListener('aborted', onAborted);
        };
        const fail = (message, statusCode) => {
            cleanup();
            request.resume();
            reject(Object.assign(new Error(message), { statusCode }));
        };
        const onData = chunk => {
            bytes += chunk.length;
            if (bytes > maxBytes) return fail('Request body exceeds the size limit', 413);
            chunks.push(chunk);
        };
        const onError = () => fail('Request body could not be read', 400);
        const onAborted = () => fail('Request was aborted', 400);
        const onEnd = () => {
            cleanup();
            try {
                const body = JSON.parse((Buffer.concat(chunks).toString('utf8') || '{}').replace(/^\uFEFF/, ''));
                if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error();
                resolve(body);
            } catch (_) { reject(Object.assign(new Error('Expected a valid JSON object'), { statusCode: 400 })); }
        };
        request.on('data', onData);
        request.once('end', onEnd);
        request.once('error', onError);
        request.once('aborted', onAborted);
    });
}

module.exports = { readJsonBody };
