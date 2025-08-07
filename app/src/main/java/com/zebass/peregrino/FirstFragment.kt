package com.zebass.peregrino

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.zebass.peregrino.databinding.FragmentFirstBinding
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!
    private val client = OkHttpClient()
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        sharedPreferences = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        return binding.root
    }

    // REEMPLAZAR la parte de verificación de login en onViewCreated:
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ✅ VERIFICAR SI YA HAY UN USUARIO LOGUEADO (INCLUYENDO PASSWORD)
        val savedToken = sharedPreferences.getString("jwt_token", null)
        val savedEmail = sharedPreferences.getString("user_email", null)
        val savedPassword = sharedPreferences.getString("user_password", null)

        if (savedToken != null && savedEmail != null && savedPassword != null) {
            navigateToSecondFragment(savedToken, savedEmail)
        }

        binding.buttonLogin.setOnClickListener {
            val email = binding.editTextEmail.text.toString().trim()
            val password = binding.editTextPassword.text.toString().trim()
            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(requireContext(), "Completa email y contraseña", Toast.LENGTH_SHORT).show()
            } else {
                login(email, password)
            }
        }

        binding.buttonRegister.setOnClickListener {
            val email = binding.editTextEmail.text.toString().trim()
            val password = binding.editTextPassword.text.toString().trim()
            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(requireContext(), "Completa email y contraseña", Toast.LENGTH_SHORT).show()
            } else {
                register(email, password)
            }
        }
    }
    private fun register(email: String, password: String) {
        val json = JSONObject().apply {
            put("email", email)
            put("password", password)
        }

        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("https://carefully-arriving-shepherd.ngrok-free.app/api/auth/register")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                requireActivity().runOnUiThread {
                    Toast.makeText(
                        requireContext(),
                        "Error de conexión. Verifica tu conexión a internet",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val bodyString = it.body?.string()
                        val jsonResponse = bodyString?.let { JSONObject(it) }
                        val token = jsonResponse?.getString("token") ?: ""

                        // ✅ GUARDAR TAMBIÉN EL PASSWORD
                        with(sharedPreferences.edit()) {
                            putString("jwt_token", token)
                            putString("user_email", email)
                            putString("user_password", password) // ✅ AGREGAR ESTA LÍNEA
                            apply()
                        }

                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), "Registro exitoso", Toast.LENGTH_SHORT).show()
                            navigateToSecondFragment(token, email)
                        }
                    } else {
                        requireActivity().runOnUiThread {
                            val errorMsg = when (response.code) {
                                400 -> "Usuario ya registrado"
                                else -> "Error al registrar: ${response.code}"
                            }
                            Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }

    private fun login(email: String, password: String) {
        val json = JSONObject().apply {
            put("email", email)
            put("password", password)
        }

        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("https://carefully-arriving-shepherd.ngrok-free.app/api/auth/login")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                requireActivity().runOnUiThread {
                    Toast.makeText(
                        requireContext(),
                        "Error de conexión. Verifica tu conexión a internet",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val bodyString = it.body?.string()
                        val jsonResponse = bodyString?.let { JSONObject(it) }
                        val token = jsonResponse?.getString("token") ?: ""

                        // ✅ GUARDAR TAMBIÉN EL PASSWORD
                        with(sharedPreferences.edit()) {
                            putString("jwt_token", token)
                            putString("user_email", email)
                            putString("user_password", password) // ✅ AGREGAR ESTA LÍNEA
                            apply()
                        }

                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), "Inicio de sesión exitoso", Toast.LENGTH_SHORT).show()
                            navigateToSecondFragment(token, email)
                        }
                    } else {
                        requireActivity().runOnUiThread {
                            val errorMsg = when (response.code) {
                                401 -> "Contraseña incorrecta"
                                404 -> "Usuario no encontrado"
                                else -> "Error al iniciar sesión: ${response.code}"
                            }
                            Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }
    private fun navigateToSecondFragment(token: String, email: String) {
        // Verificar que no estamos ya en el SecondFragment
        if (findNavController().currentDestination?.id != R.id.SecondFragment) {
            // Pasar datos como argumentos
            val action = FirstFragmentDirections.actionFirstFragmentToSecondFragment(
                jwtToken = token,
                userEmail = email
            )
            findNavController().navigate(action)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}