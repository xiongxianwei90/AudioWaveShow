/**
 * Copyright 2011, Felix Palmer
 *
 * Licensed under the MIT license:
 * http://creativecommons.org/licenses/MIT/
 */
package com.tian.audio.wave.renderer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.tian.audio.wave.dao.AudioData
import com.tian.audio.wave.dao.FFTData
import kotlin.math.*

/**
 * 操作画笔进行各个bar的绘制工作
 */
class BarGraphRenderer
/**
 * Renders the FFT data as a series of lines, in histogram form
 * @param divisions - must be a power of 2. Controls how many lines to draw
 * @param paint - Paint to draw lines with
 * @param top - whether to draw the lines at the top of the canvas, or the bottom
 */(
    private val mDivisions: Int,
    private val mPaint: Paint,
    private val mTop: Boolean
) : Renderer() {
    var frequencyBands: Int = 80 //频带数量
    var startFrequency: Float = 100f //起始频率
    var endFrequency: Float = 18000f //截止频率
    var fftSize: Int = 2048
    var sampleRate = 44100.0f
    var spectrumBuffer = arrayListOf<Channel<Float, Float>>()
    val bands by lazy {
        // 频带计算
        var bands = arrayListOf<Bands<Float, Float>>()
        //1：根据起止频谱、频带数量确定增长的倍数：2^n
        var n = log2(endFrequency / startFrequency) / frequencyBands
        var nextBand: Bands<Float, Float> = Bands(startFrequency, 0f)

        for (i in 1..frequencyBands) {
            //2：频带的上频点是下频点的2^n倍
            var highFrequency = nextBand.lowerFrequency * 2f.pow(n)
            nextBand.upperFrequency = if (i == frequencyBands) {
                endFrequency
            } else {
                highFrequency
            }
            bands.add(nextBand)
            nextBand.lowerFrequency = highFrequency
        }
        bands
    }

    override fun onRender(canvas: Canvas, data: AudioData, rect: Rect) {
        // Do nothing, we only display FFT data
    }

    override fun onRender(canvas: Canvas, data: FFTData, rect: Rect) {
        analyse(data)
        spectrumBuffer.forEachIndexed { i, dbValue ->
            if (i * 4 < mFFTPoints.size) {
                mFFTPoints[i * 4] = i * 4f * mDivisions
                mFFTPoints[i * 4 + 2] = i * 4f * mDivisions
                mFFTPoints[i * 4 + 1] = rect.height().toFloat()
                mFFTPoints[i * 4 + 3] = rect.height().toFloat() - dbValue.left
            }
        }
        canvas.drawLines(mFFTPoints, mPaint)
    }

    /**
     * 用来计算新频带的值，采用的方法是找出落在该频带范围内的原始振幅数据的最大值：
     */
    fun findMaxAmplitude(
        band: Bands<Float, Float>,
        amplitudes: FloatArray,
        bandWidth: Float
    ): Float {
        var startIndex = (band.lowerFrequency / bandWidth).roundToInt()
        var endIndex = min((band.upperFrequency / bandWidth).roundToInt(), amplitudes.size - 1)
        var temp = 0f
        for (i in startIndex..endIndex) {
            temp = max(amplitudes[i], temp)
        }
        return temp
    }

    /**
     * 这样就可以通过新的analyse函数接收音频原始数据并向外提供加工好的频谱数据：
     */
    fun analyse(data: FFTData) {
        var spectra = arrayListOf<Channel<Float, Float>>()

        var aWeights = createFrequencyWeights()
        var magnitude = data.bytes.mapIndexed { index, rfk ->
            val ifk = if (index + 1 < data.bytes.size) {
                data.bytes[index + 1]
            } else {
                0
            }
            var magnitude = (rfk * rfk + ifk * ifk).toFloat()
            var dbValue = (10 * log10(magnitude.toDouble())).toFloat()
            if (dbValue.isInfinite()) {
                dbValue = 0f
            }
            (dbValue * aWeights[index]).toFloat()
        }.toFloatArray()

        var spectrum = bands.map {
            findMaxAmplitude(
                it, magnitude,
                sampleRate / fftSize.toFloat()
            ) * 5
        }.toFloatArray()
        magnitude = highlightWaveform(magnitude)
//
        if (spectrumBuffer.size != 0) {
            spectrumBuffer.clear()
        }
        magnitude.forEachIndexed { index, t ->
            spectrumBuffer.add(Channel(t * 5, t * 5))
        }

    }

    /**
     * awaiting 算法
     */
    fun createFrequencyWeights(): List<Double> {
        var Δf = 44100.0 / fftSize.toFloat()
        var bins = fftSize / 2

        var f = (0 until bins).map { it.toFloat() * Δf }
        f = f.map { it * it }

        var c1 = 12194.217f.pow(2.0f)
        var c2 = 20.598997f.pow(2.0f)
        var c3 = 107.65265f.pow(2.0f)
        var c4 = 737.86223f.pow(2.0f)

        var num = f.map { c1 * it * it }
        var den = f.map { (it + c2) * sqrt((it + c3) * (it + c4)) * (it + c1) }
        var weights = num.mapIndexed { index, ele ->
            1.2589 * ele / den[index]
        }
        return weights
    }


    private fun highlightWaveform(spectrum: FloatArray): FloatArray {
        //1: 定义权重数组，数组中间的5表示自己的权重
        //   可以随意修改，个数需要奇数
        var weights: FloatArray = floatArrayOf(1f, 2f, 3f, 5f, 3f, 2f, 1f)

        var totalWeights = weights.reduce { acc, fl -> acc + fl }
        var startIndex = weights.size / 2
        //2: 开头几个不参与计算
        var averagedSpectrum = spectrum.slice(0 until startIndex).toMutableList()
        for (i in startIndex until spectrum.size - startIndex) {
            //3: zip作用: zip([a,b,c], [x,y,z]) -> [(a,x), (b,y), (c,z)]
//            var zipped = zip(Array(spectrum[i - startIndex...i + startIndex]), weights)
            var start = i - startIndex
            var end = i + startIndex
            var zipped = spectrum.slice(start..end)
            var averaged = zipped.reduceIndexed { index, acc, fl ->
                acc + fl * weights[index]
            } / totalWeights
            averagedSpectrum.add(averaged)
        }

        //4：末尾几个不参与计算
        averagedSpectrum.addAll(spectrum.slice((spectrum.size - startIndex)..(spectrum.size - 1)))
        return averagedSpectrum.toFloatArray()
    }

    data class Bands<A, B>(var lowerFrequency: A, var upperFrequency: B)
    data class Channel<A, B>(var left: A, var right: B)
}