class MseDecoder {
    constructor(onError) {
        this.video = document.getElementById('mse-video');
        this.mediaSource = null;
        this.sourceBuffer = null;
        this.onError = onError;

        this.queue = [];
        this.updating = false;
        this.ready = false;

        this.codec = null;          // e.g. "avc1.640028"
        this.pendingCodec = null;    // codec hint pushed by the server before init arrives
        this.initSegment = null;     // buffered fMP4 init until SourceBuffer exists

        this.firstFrameSignaled = false;
        this.onFirstFrame = null;

        this.framesDecoded = 0;
        this.lastDecodeTime = 0;
        this.videoDuration = 0;

        // Target latency management (see MseDecoder.latencyAction)
        this.targetLatency = MseDecoder.TARGET_LATENCY;
        this.latencyCheckInterval = null;
    }

    /**
     * Live-edge steering for a jittery LTE + tunnel link. Frames arrive in
     * bursts, so latency spikes are normal; the old policy hard-seeked whenever
     * latency passed 0.5 s, and every seek flushes the (hardware) decoder — a
     * visible hitch several times a minute. Now: speed playback up slightly to
     * drain a backlog, and only seek when we are hopelessly behind.
     * Returns { seek, rate } or null (inside the hysteresis band: keep the rate).
     */
    static latencyAction(latency) {
        if (!(latency >= 0)) return null;
        if (latency > MseDecoder.HARD_SEEK_LATENCY) return { seek: true, rate: 1.0 };
        if (latency > MseDecoder.CATCHUP_START_LATENCY) return { seek: false, rate: MseDecoder.CATCHUP_RATE };
        if (latency < MseDecoder.CATCHUP_STOP_LATENCY) return { seek: false, rate: 1.0 };
        return null;
    }

    static isSupported() {
        return typeof MediaSource !== 'undefined' &&
            MediaSource.isTypeSupported('video/mp4; codecs="avc1.42001e"');
    }

    init() {
        return new Promise((resolve, reject) => {
            if (!this.video) {
                console.error('[MSE] Video element #mse-video not found in DOM');
                reject(new Error('Video element not found'));
                return;
            }

            this.mediaSource = new MediaSource();

            this.video.addEventListener('error', (e) => {
                const err = this.video.error;
                console.error('[MSE] Video element error:', err ? `code=${err.code} message=${err.message}` : e);
            });

            this.mediaSource.addEventListener('sourceopen', () => {
                try {
                    this.startTime = performance.now();
                    console.log('[MSE] MediaSource opened, waiting for init segment');
                    resolve();
                } catch (e) {
                    reject(e);
                }
            });

            this.mediaSource.addEventListener('sourceclose', () => {
                console.log('[MSE] MediaSource closed');
                this.ready = false;
            });

            this.mediaSource.addEventListener('sourceended', () => {
                console.log('[MSE] MediaSource ended');
            });

            this._objectUrl = URL.createObjectURL(this.mediaSource);
            this.video.src = this._objectUrl;

            // Show the video; keep the canvas as a transparent touch overlay on top.
            this.video.style.display = 'block';
            this.video.style.opacity = '1';
            const canvas = document.getElementById('display');
            if (canvas) {
                canvas.style.display = 'block';
                canvas.style.opacity = '0';
            }

            this.startLatencyManagement();
        });
    }

    setupSourceBuffer(codecString) {
        if (!this.mediaSource || this.mediaSource.readyState !== 'open') {
            console.error('[MSE] MediaSource not open, cannot create SourceBuffer');
            return false;
        }

        try {
            if (this.sourceBuffer) {
                this.mediaSource.removeSourceBuffer(this.sourceBuffer);
                this.sourceBuffer = null;
            }

            console.log(`[MSE] Creating SourceBuffer with codec: ${codecString}`);
            this.sourceBuffer = this.mediaSource.addSourceBuffer(codecString);
            this.sourceBuffer.mode = 'sequence'; // let the browser sequence timestamps

            this.sourceBuffer.addEventListener('updateend', () => {
                this.updating = false;
                this.processQueue();
            });

            this.sourceBuffer.addEventListener('error', (e) => {
                console.error('[MSE] SourceBuffer error:', e);
                if (this.onError) this.onError(e);
            });

            this.ready = true;
            console.log('[MSE] SourceBuffer created and ready');
            return true;
        } catch (e) {
            console.error('[MSE] Failed to create SourceBuffer:', e);
            return false;
        }
    }

    processQueue() {
        if (this.updating || !this.sourceBuffer || !this.ready) return;

        try {
            if (this.initSegment) {
                const init = this.initSegment;
                this.initSegment = null;
                this.updating = true;
                this.sourceBuffer.appendBuffer(init);
                this.signalFirstFrame();
                return;
            }
            if (this.queue.length === 0) return;
            const data = this.queue.shift();
            this.updating = true;
            this.sourceBuffer.appendBuffer(data);
            this.framesDecoded++;
            this.lastDecodeTime = performance.now();
            this.signalFirstFrame();
        } catch (e) {
            this.updating = false;
            console.error('[MSE] appendBuffer error:', e);
            if (e && e.name === 'QuotaExceededError' && this.sourceBuffer) {
                this.flushBuffer();
            }
        }
    }

    signalFirstFrame() {
        if (!this.firstFrameSignaled) {
            this.firstFrameSignaled = true;
            if (this.onFirstFrame) this.onFirstFrame();
        }
    }

    flushBuffer() {
        if (this.updating || !this.sourceBuffer || !this.video) return;
        try {
            const currentTime = this.video.currentTime;
            if (currentTime > 2) {
                this.updating = true;
                this.sourceBuffer.remove(0, currentTime - 1);
                console.log(`[MSE] Flushed buffer up to ${currentTime - 1}`);
            }
        } catch (e) {
            console.error('[MSE] flush error:', e);
            this.updating = false;
        }
    }

    startLatencyManagement() {
        this.latencyCheckInterval = setInterval(() => {
            if (!this.video || !this.sourceBuffer || this.video.buffered.length === 0) return;

            const bufferedEnd = this.video.buffered.end(this.video.buffered.length - 1);
            const currentTime = this.video.currentTime;
            const latency = bufferedEnd - currentTime;

            const action = MseDecoder.latencyAction(latency);
            if (action) {
                if (action.seek) {
                    console.log(`[MSE] Latency ${latency.toFixed(2)}s — seeking to live edge.`);
                    this.video.currentTime = Math.max(0, bufferedEnd - this.targetLatency);
                }
                if (this.video.playbackRate !== action.rate) this.video.playbackRate = action.rate;
            }

            // A stall (e.g. after a burst gap) can leave the element paused.
            if (this.video.paused && this.video.readyState >= 2) {
                const p = this.video.play();
                if (p && p.catch) p.catch(() => {});
            }

            if (currentTime > 10 && !this.updating) {
                this.flushBuffer();
            }
        }, 500);
    }

    decode(data) {
        if (!data || data.byteLength < 8) return;

        const view = new DataView(data);
        const flags = view.getUint8(0);
        const isInit = flags === 0x02;
        // Payload begins after the 8-byte network header shared with the H264/MJPEG paths.
        const payload = new Uint8Array(data, 8);

        if (isInit) {
            if (!this.codec) {
                this.codec = this.parseCodecFromInit(payload) || this.pendingCodec;
            }
            if (!this.codec) {
                console.warn('[MSE] No codec string available yet; buffering init segment');
                this.initSegment = payload;
                return;
            }
            if (!this.sourceBuffer) {
                this.setupSourceBuffer(this.codec);
            }
            this.initSegment = payload;
            this.processQueue();
            return;
        }

        if (!this.ready || !this.sourceBuffer) return;
        this.queue.push(payload);
        this.processQueue();
    }

    /**
     * Parse the codec string (avc1.HHCCLL) out of an fMP4 init segment by walking
     * the ISO-BMFF box tree to the avcC box. Self-contained so no out-of-band
     * codec hint is strictly required.
     */
    parseCodecFromInit(init) {
        try {
            const n = init.length;
            let found = null;
            let i = 0;
            while (i + 8 <= n && !found) {
                const size = (init[i] << 24) | (init[i + 1] << 16) | (init[i + 2] << 8) | init[i + 3];
                const type = String.fromCharCode(init[i + 4], init[i + 5], init[i + 6], init[i + 7]);
                if (type === 'moov') {
                    const moovEnd = i + size;
                    let j = i + 8;
                    while (j + 8 <= moovEnd && !found) {
                        const s2 = (init[j] << 24) | (init[j + 1] << 16) | (init[j + 2] << 8) | init[j + 3];
                        const t2 = String.fromCharCode(init[j + 4], init[j + 5], init[j + 6], init[j + 7]);
                        if (t2 === 'trak') {
                            const trakEnd = j + s2;
                            let k = j + 8;
                            while (k + 8 <= trakEnd && !found) {
                                const s3 = (init[k] << 24) | (init[k + 1] << 16) | (init[k + 2] << 8) | init[k + 3];
                                const t3 = String.fromCharCode(init[k + 4], init[k + 5], init[k + 6], init[k + 7]);
                                if (t3 === 'mdia') {
                                    const mdiaEnd = k + s3;
                                    let m = k + 8;
                                    while (m + 8 <= mdiaEnd && !found) {
                                        const s4 = (init[m] << 24) | (init[m + 1] << 16) | (init[m + 2] << 8) | init[m + 3];
                                        const t4 = String.fromCharCode(init[m + 4], init[m + 5], init[m + 6], init[m + 7]);
                                        if (t4 === 'minf') {
                                            const minfEnd = m + s4;
                                            let p = m + 8;
                                            while (p + 8 <= minfEnd && !found) {
                                                const s5 = (init[p] << 24) | (init[p + 1] << 16) | (init[p + 2] << 8) | init[p + 3];
                                                const t5 = String.fromCharCode(init[p + 4], init[p + 5], init[p + 6], init[p + 7]);
                                                if (t5 === 'stbl') {
                                                    const stblEnd = p + s5;
                                                    let q = p + 8;
                                                    while (q + 8 <= stblEnd && !found) {
                                                        const s6 = (init[q] << 24) | (init[q + 1] << 16) | (init[q + 2] << 8) | init[q + 3];
                                                        const t6 = String.fromCharCode(init[q + 4], init[q + 5], init[q + 6], init[q + 7]);
                                                        if (t6 === 'stsd') {
                                                            let r = q + 8 + 4 + 4; // stsd header + entry_count
                                                            const s7 = (init[r] << 24) | (init[r + 1] << 16) | (init[r + 2] << 8) | init[r + 3];
                                                            const t7 = String.fromCharCode(init[r + 4], init[r + 5], init[r + 6], init[r + 7]);
                                                            if (t7 === 'avc1') {
                                                                const avc1End = r + s7;
                                                                let a = r + 8;
                                                                while (a + 8 <= avc1End && !found) {
                                                                    const s8 = (init[a] << 24) | (init[a + 1] << 16) | (init[a + 2] << 8) | init[a + 3];
                                                                    const t8 = String.fromCharCode(init[a + 4], init[a + 5], init[a + 6], init[a + 7]);
                                                                    if (t8 === 'avcC') {
                                                                        const o = a + 8;
                                                                        const toHex = (b) => (b & 0xFF).toString(16).padStart(2, '0');
                                                                        found = `avc1.${toHex(init[o + 1])}${toHex(init[o + 2])}${toHex(init[o + 3])}`;
                                                                    } else {
                                                                        if (s8 <= 0 || a + s8 > avc1End) break;
                                                                        a += s8;
                                                                    }
                                                                }
                                                            } else {
                                                                if (s7 <= 0 || r + s7 > avc1End) break;
                                                                r += s7;
                                                            }
                                                        } else {
                                                            if (s6 <= 0 || q + s6 > stblEnd) break;
                                                            q += s6;
                                                        }
                                                    }
                                                } else {
                                                    if (s5 <= 0 || p + s5 > minfEnd) break;
                                                    p += s5;
                                                }
                                            }
                                        } else {
                                            if (s4 <= 0 || m + s4 > mdiaEnd) break;
                                            m += s4;
                                        }
                                    }
                                } else {
                                    if (s3 <= 0 || k + s3 > trakEnd) break;
                                    k += s3;
                                }
                            }
                        } else {
                            if (s2 <= 0 || j + s2 > moovEnd) break;
                            j += s2;
                        }
                    }
                } else {
                    if (size <= 0 || i + size > n) break;
                    i += size;
                }
            }
            return found;
        } catch (e) {
            console.error('[MSE] parseCodecFromInit failed:', e);
            return null;
        }
    }

    play() {
        if (this.video && this.video.paused) {
            this.video.play().catch(e => console.error('[MSE] Playback failed:', e));
        }
    }

    destroy() {
        if (this.latencyCheckInterval) {
            clearInterval(this.latencyCheckInterval);
            this.latencyCheckInterval = null;
        }

        if (this.mediaSource && this.mediaSource.readyState === 'open') {
            try {
                if (this.sourceBuffer) {
                    this.mediaSource.removeSourceBuffer(this.sourceBuffer);
                }
                this.mediaSource.endOfStream();
            } catch (e) {
                console.error('[MSE] Error closing MediaSource:', e);
            }
        }

        if (this._objectUrl) {
            URL.revokeObjectURL(this._objectUrl);
            this._objectUrl = null;
        }
        this.video = null;
        this.mediaSource = null;
        this.sourceBuffer = null;
        this.queue = [];
        this.initSegment = null;
        this.ready = false;
    }
}

MseDecoder.TARGET_LATENCY = 0.3;        // where a hard seek lands (s behind live edge)
MseDecoder.CATCHUP_START_LATENCY = 0.6; // start playing faster above this
MseDecoder.CATCHUP_STOP_LATENCY = 0.35; // back to 1x below this
MseDecoder.CATCHUP_RATE = 1.1;
MseDecoder.HARD_SEEK_LATENCY = 2.0;     // hopelessly behind: jump

if (typeof module !== 'undefined' && module.exports) module.exports = { MseDecoder };
