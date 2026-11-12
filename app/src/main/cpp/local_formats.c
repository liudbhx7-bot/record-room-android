/* Local APE, WavPack and AIFF input. The fd is granted by Android. No paths or
 * URLs are passed to libavformat. PCM goes directly to the Media3 audio path. */
#include <jni.h>
#include <errno.h>
#include <limits.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/mem.h>

typedef struct {
    int fd, stream, channels, rate, bytes, floating, valid_bits, draining, index;
    int64_t base, limit, offset, samples, seek_target, next_sample;
    AVIOContext *io;
    AVFormatContext *format;
    AVCodecContext *codec;
    AVFrame *frame;
    AVPacket *packet;
} Reader;

static int read_fd(void *opaque, uint8_t *buf, int size) {
    Reader *r = opaque;
    if (size > r->limit - r->offset) size = (int)(r->limit - r->offset);
    if (size <= 0) return AVERROR_EOF;
    ssize_t n;
    do { n = pread(r->fd, buf, size, r->base + r->offset); } while (n < 0 && errno == EINTR);
    if (n < 0) return AVERROR(errno);
    if (!n) return AVERROR_EOF;
    r->offset += n;
    return (int)n;
}

static int64_t seek_fd(void *opaque, int64_t offset, int whence) {
    Reader *r = opaque;
    whence &= ~AVSEEK_FORCE;
    if (whence == AVSEEK_SIZE) return r->limit;
    int64_t start;
    switch (whence) {
        case SEEK_SET: start = 0; break;
        case SEEK_CUR: start = r->offset; break;
        case SEEK_END: start = r->limit; break;
        default: return AVERROR(EINVAL);
    }
    if (offset < -start || offset > r->limit - start) return AVERROR(EINVAL);
    return r->offset = start + offset;
}

static void close_reader(Reader *r) {
    if (!r) return;
    av_frame_free(&r->frame);
    av_packet_free(&r->packet);
    avcodec_free_context(&r->codec);
    avformat_close_input(&r->format);
    if (r->io) av_freep(&r->io->buffer);
    avio_context_free(&r->io);
    if (r->fd >= 0) close(r->fd);
    av_free(r);
}

static void fail(JNIEnv *env, const char *message) {
    jclass io = (*env)->FindClass(env, "java/io/IOException");
    if (io) (*env)->ThrowNew(env, io, message);
}

#define JNI_READER(method) Java_com_recordroom_vinyl_LocalFormatDataSource_##method
JNIEXPORT jlong JNICALL JNI_READER(openReader)(JNIEnv *env, jclass klass, jint fd, jlong base,
                                               jlong length, jint kind, jlongArray info) {
    Reader *r = av_mallocz(sizeof(*r));
    if (!r) goto error;
    r->fd = dup(fd);
    if (r->fd < 0 || base < 0 || length <= 0 || base > INT64_MAX-length) goto error;
    r->base = base; r->limit = length;
    unsigned char *buffer = av_malloc(32768);
    if (!buffer) goto error;
    r->io = avio_alloc_context(buffer, 32768, 0, r, read_fd, NULL, seek_fd);
    if (!r->io) { av_free(buffer); goto error; }
    r->format = avformat_alloc_context();
    if (!r->format) goto error;
    r->format->pb = r->io;
    r->format->flags |= AVFMT_FLAG_CUSTOM_IO;
    const char *demux = kind == 1 ? "ape" : kind == 2 ? "wv" : kind == 3 ? "aiff" : "mov";
    const AVInputFormat *input = av_find_input_format(demux);
    if (!input || avformat_open_input(&r->format, NULL, input, NULL) < 0) {
        if (kind == 4) { close_reader(r); return 0; }
        goto error;
    }
    if (kind == 4) {
        // AAC, DRM streams and video continue through the existing platform path.
        int audio = av_find_best_stream(r->format, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
        if (audio < 0 || r->format->streams[audio]->codecpar->codec_id != AV_CODEC_ID_ALAC) {
            close_reader(r); return 0;
        }
    }
    if (avformat_find_stream_info(r->format, NULL) < 0) goto error;
    const AVCodec *decoder = NULL;
    r->stream = av_find_best_stream(r->format, AVMEDIA_TYPE_AUDIO, -1, -1, &decoder, 0);
    if (r->stream < 0 || !decoder) goto error;
    AVStream *s = r->format->streams[r->stream];
    r->codec = avcodec_alloc_context3(decoder);
    if (!r->codec || avcodec_parameters_to_context(r->codec, s->codecpar) < 0) goto error;
    r->codec->pkt_timebase = s->time_base;
    r->codec->thread_count = 1;
    if (avcodec_open2(r->codec, decoder, NULL) < 0) goto error;
    r->channels = r->codec->ch_layout.nb_channels; r->rate = r->codec->sample_rate;
    if (r->channels < 1 || r->channels > 8 || r->rate < 1 || r->rate > 768000) goto error;
    enum AVSampleFormat f = av_get_packed_sample_fmt(r->codec->sample_fmt);
    r->floating = f == AV_SAMPLE_FMT_FLT || f == AV_SAMPLE_FMT_DBL;
    r->valid_bits = r->codec->bits_per_raw_sample;
    if (!r->valid_bits) r->valid_bits = s->codecpar->bits_per_coded_sample;
    if (!r->valid_bits) r->valid_bits = av_get_bytes_per_sample(f) * 8;
    if (f != AV_SAMPLE_FMT_U8 && f != AV_SAMPLE_FMT_S16 && f != AV_SAMPLE_FMT_S32 && !r->floating) goto error;
    r->bytes = r->floating || r->valid_bits > 24 ? 4 : r->valid_bits > 16 ? 3 : 2;
    if (s->duration <= 0) goto error;
    r->samples = av_rescale_q(s->duration, s->time_base, (AVRational){1,r->rate});
    if (r->samples <= 0 || r->samples > (INT64_MAX-128) / (r->channels*r->bytes)) goto error;
    r->packet=av_packet_alloc(); r->frame=av_frame_alloc();
    if (!r->packet || !r->frame) goto error;
    jlong values[] = {r->rate,r->channels,r->bytes,r->floating,r->samples,r->valid_bits};
    (*env)->SetLongArrayRegion(env, info, 0, 6, values);
    return (jlong)(intptr_t)r;
error:
    close_reader(r); fail(env,"不支持或损坏的本地音频"); return 0;
}

JNIEXPORT void JNICALL JNI_READER(seekReader)(JNIEnv *env,jclass klass,jlong handle,jlong sample) {
    Reader *r=(Reader*)(intptr_t)handle;
    if (!sample) return;
    AVStream *s=r->format->streams[r->stream];
    int64_t start=s->start_time==AV_NOPTS_VALUE?0:s->start_time;
    int64_t ts=start+av_rescale_q(sample,(AVRational){1,r->rate},s->time_base);
    // A fresh reader is positioned at the beginning if the demuxer has no fast seek.
    if (av_seek_frame(r->format,r->stream,ts,AVSEEK_FLAG_BACKWARD)>=0) avcodec_flush_buffers(r->codec);
    r->seek_target=sample;
}

static int next_frame(Reader *r) {
    for (;;) {
        int err=avcodec_receive_frame(r->codec,r->frame);
        if (err==0) {
            AVFrame *f=r->frame;
            if(f->ch_layout.nb_channels!=r->channels || f->sample_rate!=r->rate) return AVERROR_INVALIDDATA;
            AVStream *s=r->format->streams[r->stream];
            int64_t first=r->next_sample;
            if (f->best_effort_timestamp!=AV_NOPTS_VALUE) {
                int64_t start=s->start_time==AV_NOPTS_VALUE?0:s->start_time;
                first=av_rescale_q(f->best_effort_timestamp-start,s->time_base,(AVRational){1,r->rate});
            }
            r->next_sample=first+f->nb_samples;
            if (r->seek_target>first && r->seek_target>=r->next_sample) { av_frame_unref(f); continue; }
            r->index=r->seek_target>first?(int)(r->seek_target-first):0;
            r->seek_target=0; return 0;
        }
        if (err!=AVERROR(EAGAIN) || r->draining) return err;
        do {
            av_packet_unref(r->packet);
            err=av_read_frame(r->format,r->packet);
        } while(err==0 && r->packet->stream_index!=r->stream);
        if (err<0 && err!=AVERROR_EOF) return err;
        r->draining=err==AVERROR_EOF;
        err=avcodec_send_packet(r->codec,r->draining?NULL:r->packet);
        av_packet_unref(r->packet);
        if (err<0) return err;
    }
}

JNIEXPORT jint JNICALL JNI_READER(readPcm)(JNIEnv *env,jclass klass,jlong handle,jbyteArray target) {
    Reader *r=(Reader*)(intptr_t)handle;
    if (r->index>=r->frame->nb_samples) {
        av_frame_unref(r->frame);
        int err=next_frame(r);
        if(err==AVERROR_EOF) return -1;
        if(err<0) { fail(env,"本地音频解码失败"); return -1; }
    }
    AVFrame *f=r->frame;
    int samples=(*env)->GetArrayLength(env,target)/(r->channels*r->bytes);
    if(samples>f->nb_samples-r->index) samples=f->nb_samples-r->index;
    enum AVSampleFormat fmt=av_get_packed_sample_fmt(f->format);
    int planar=av_sample_fmt_is_planar(f->format);
    jbyte *out=(*env)->GetByteArrayElements(env,target,NULL);
    if (!out) return -1;
    uint8_t *dst=(uint8_t*)out;
    for(int i=r->index;i<r->index+samples;i++) for(int ch=0;ch<r->channels;ch++) {
        uint8_t *data=f->extended_data[planar?ch:0];
        int j=planar?i:i*r->channels+ch;
        uint32_t bits;
        if(r->floating) {
            float v=fmt==AV_SAMPLE_FMT_DBL?(float)((double*)data)[j]:((float*)data)[j];
            memcpy(&bits,&v,4);
        } else {
            if(fmt==AV_SAMPLE_FMT_S32) bits=((uint32_t*)data)[j];
            else if(fmt==AV_SAMPLE_FMT_S16) bits=(uint32_t)((uint16_t*)data)[j]<<16;
            else bits=(uint32_t)(data[j]^128)<<24;
            bits >>= (4-r->bytes)*8;
        }
        for(int b=0;b<r->bytes;b++) *dst++=(uint8_t)(bits>>(8*b));
    }
    (*env)->ReleaseByteArrayElements(env,target,out,0);
    r->index+=samples;
    return samples*r->channels*r->bytes;
}

JNIEXPORT void JNICALL JNI_READER(closeReader)(JNIEnv *env,jclass klass,jlong handle) {
    close_reader((Reader*)(intptr_t)handle);
}

JNIEXPORT jbyteArray JNICALL JNI_READER(tag)(JNIEnv *env,jclass klass,jlong handle,jstring name) {
    Reader *r=(Reader*)(intptr_t)handle;
    const char *key=(*env)->GetStringUTFChars(env,name,NULL);
    if(!key) return NULL;
    const AVDictionaryEntry *entry=av_dict_get(r->format->metadata,key,NULL,0);
    if(!entry) entry=av_dict_get(r->format->streams[r->stream]->metadata,key,NULL,0);
    (*env)->ReleaseStringUTFChars(env,name,key);
    if(!entry) return NULL;
    size_t len=strnlen(entry->value,65536);
    jbyteArray bytes=(*env)->NewByteArray(env,len);
    if(bytes) (*env)->SetByteArrayRegion(env,bytes,0,len,(const jbyte*)entry->value);
    return bytes;
}

JNIEXPORT jbyteArray JNICALL JNI_READER(picture)(JNIEnv *env,jclass klass,jlong handle) {
    Reader *r=(Reader*)(intptr_t)handle;
    for(unsigned i=0;i<r->format->nb_streams;i++) {
        AVStream *s=r->format->streams[i];
        if(!(s->disposition & AV_DISPOSITION_ATTACHED_PIC) || s->attached_pic.size<=0 ||
             s->attached_pic.size>16*1024*1024) continue;
        jbyteArray img=(*env)->NewByteArray(env,s->attached_pic.size);
        if(img) (*env)->SetByteArrayRegion(env,img,0,s->attached_pic.size,(const jbyte*)s->attached_pic.data);
        return img;
    }
    return NULL;
}
