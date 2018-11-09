package it.unive.dais.legodroid.lib;

import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import it.unive.dais.legodroid.lib.comm.AsyncChannel;
import it.unive.dais.legodroid.lib.comm.Bytecode;
import it.unive.dais.legodroid.lib.comm.Channel;
import it.unive.dais.legodroid.lib.comm.Const;
import it.unive.dais.legodroid.lib.comm.Reply;
import it.unive.dais.legodroid.lib.comm.SpooledAsyncChannel;
import it.unive.dais.legodroid.lib.motors.TachoMotor;
import it.unive.dais.legodroid.lib.sensors.GyroSensor;
import it.unive.dais.legodroid.lib.sensors.LightSensor;
import it.unive.dais.legodroid.lib.sensors.TouchSensor;
import it.unive.dais.legodroid.lib.sensors.UltrasonicSensor;
import it.unive.dais.legodroid.lib.util.Consumer;
import it.unive.dais.legodroid.lib.util.UnexpectedException;

import static it.unive.dais.legodroid.lib.comm.Const.ReTAG;

public class EV3 {
    private static final String TAG = ReTAG("EV3");

    @NonNull
    private final AsyncChannel channel;
    @Nullable
    private AsyncTask<Void, Void, Void> task = null;

    public EV3(@NonNull AsyncChannel channel) {
        this.channel = channel;
    }

    public EV3(@NonNull Channel channel) {
        this(new SpooledAsyncChannel(channel));
    }

    public synchronized void run(@NonNull Consumer<Api> f) {
        if (task != null) throw new IllegalStateException("EV3 is already running a task");
        task = new MyAsyncTask(this, f).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static class MyAsyncTask extends AsyncTask<Void, Void, Void> {
        private final String TAG = ReTAG(EV3.TAG, "AsyncTask");

        @NonNull
        private final EV3 ev3;
        @NonNull
        private final Consumer<Api> f;

        private MyAsyncTask(@NonNull EV3 ev3, @NonNull Consumer<Api> f) {
            this.ev3 = ev3;
            this.f = f;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            Thread.currentThread().setName(TAG);
            Log.v(TAG, "starting task");
            try {
                f.call(new Api(ev3));
            } catch (Exception e) {
                Log.e(TAG, String.format("uncaught exception: %s. Aborting.", e.getMessage()));
                e.printStackTrace();
            }
            synchronized (ev3) {
                ev3.task = null;
            }
            Log.v(TAG, "exiting task");
            return null;
        }
    }

    public synchronized void cancel() {
        if (task != null) {
            Log.d(TAG, "cancelling task");
            task.cancel(true);
        }
    }

    public synchronized boolean isCancelled() {
        if (task != null) {
            return task.isCancelled();
        } else return true;
    }

    public static class Api {
        @NonNull
        public final EV3 ev3;

        private Api(@NonNull EV3 ev3) {
            this.ev3 = ev3;
        }

        @NonNull
        public LightSensor getLightSensor(InputPort port) {
            return new LightSensor(this, port);
        }

        @NonNull
        public TouchSensor getTouchSensor(InputPort port) {
            return new TouchSensor(this, port);
        }

        @NonNull
        public UltrasonicSensor getUltrasonicSensor(InputPort port) {
            return new UltrasonicSensor(this, port);
        }

        @NonNull
        public GyroSensor getGyroSensor(InputPort port) {
            return new GyroSensor(this, port);
        }

        @NonNull
        public TachoMotor getTachoMotor(OutputPort port) {
            return new TachoMotor(this, port);
        }

        public void soundTone(int volume, int freq, int duration) throws IOException {
            Bytecode bc = new Bytecode();
            bc.addOpCode(Const.SOUND_CONTROL);
            bc.addOpCode(Const.SOUND_TONE);
            bc.addParameter((byte) volume);
            bc.addParameter((short) freq);
            bc.addParameter((short) duration);
            ev3.channel.sendNoReply(bc);
        }

        // low level API
        //

        private final Executor executor = Executors.newSingleThreadExecutor();

        private Bytecode prefaceGetValue(byte ready, InputPort port, int type, int mode, int nvalue) throws IOException {
            Bytecode r = new Bytecode();
            r.addOpCode(Const.INPUT_DEVICE);
            r.addOpCode(ready);
            r.addParameter(Const.LAYER_MASTER);
            r.addParameter(port.toByte());
            r.addParameter((byte) type);
            r.addParameter((byte) mode);
            r.addParameter((byte) nvalue);
            r.addGlobalIndex((byte) 0x00);
            return r;
        }

        public Future<float[]> getSiValue(InputPort port, int type, int mode, int nvalue) throws IOException {
            Bytecode bc = prefaceGetValue(Const.READY_SI, port, type, mode, nvalue);
            Future<Reply> r = ev3.channel.send(4 * nvalue, bc);
            return execAsync(() -> {
                Reply reply = r.get();
                float[] result = new float[nvalue];
                for (int i = 0; i < nvalue; i++) {
                    byte[] bData = Arrays.copyOfRange(reply.getData(), 3 + 4 * i, 7 + 4 * i);
                    result[i] = ByteBuffer.wrap(bData).order(ByteOrder.LITTLE_ENDIAN).getFloat();
                }
                return result;
            });
        }

        @NonNull
        public <T> FutureTask<T> execAsync(@NonNull Callable<T> c) {
            FutureTask<T> t = new FutureTask<>(c);
            executor.execute(t);
            return t;
        }

        @NonNull
        public Future<short[]> getPercentValue(InputPort port, int type, int mode, int nvalue) throws IOException {
            Bytecode bc = prefaceGetValue(Const.READY_PCT, port, type, mode, nvalue);
            Future<Reply> fr = ev3.channel.send(2 * nvalue, bc);
            return execAsync(() -> {
                Reply r = fr.get();
                byte[] reply = r.getData();
                short[] result = new short[nvalue];
                for (int i = 0; i < nvalue; i++) {
                    result[i] = (short) reply[i];
                }
                return result;
            });
        }

        public void setOutputSpeed(OutputPort port, int speed) throws IOException {
            Bytecode bc = new Bytecode();
            byte p = (byte) (0x01 << port.toByte());
            bc.addOpCode(Const.OUTPUT_POWER);
            bc.addParameter(Const.LAYER_MASTER);
            bc.addParameter(p);
            bc.addParameter((byte) speed);
            bc.addOpCode(Const.OUTPUT_START);
            bc.addParameter(Const.LAYER_MASTER);
            bc.addParameter(p);
            ev3.channel.sendNoReply(bc);
        }
    }

    public enum InputPort {
        _1, _2, _3, _4;

        public byte toByte() {
            switch (this) {
                case _1:
                    return 0;
                case _2:
                    return 1;
                case _3:
                    return 2;
                case _4:
                    return 3;
            }
            throw new UnexpectedException("invalid input port");
        }
    }

    public enum OutputPort {
        A, B, C, D;

        public byte toByte() {
            switch (this) {
                case A:
                    return 0;
                case B:
                    return 1;
                case C:
                    return 2;
                case D:
                    return 3;
            }
            throw new UnexpectedException("invalid output port");
        }
    }
}
