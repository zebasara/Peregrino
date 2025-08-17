package com.zebass.peregrino

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
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

    companion object {

            private const val TAG = "FirstFragment"
            // ✅ NUEVAS URLs CON CLOUDFLARE TUNNELS (HTTPS)
            private const val BASE_URL = "https://app.socialengeneering.work"
            private const val TRACCAR_URL = "https://traccar.socialengeneering.work"

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        sharedPreferences = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Log.d(TAG, "FirstFragment created - checking saved session")

        // ✅ VERIFICAR SI YA HAY UN USUARIO LOGUEADO AL CREAR LA VISTA
        checkSavedSession()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "FirstFragment resumed - checking session again")

        // ✅ VERIFICAR SESIÓN CADA VEZ QUE SE RESUME EL FRAGMENT
        checkSavedSession()
    }
    // ✅ VERSIÓN ALTERNATIVA ULTRA-ROBUSTA
    // ✅ REEMPLAZAR LAS FUNCIONES register() y login() POR ESTAS VERSIONES CORREGIDAS

    private fun register(email: String, password: String) {
        val json = JSONObject().apply {
            put("email", email)
            put("password", password)
        }

        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("$BASE_URL/api/auth/register") // ✅ HTTPS Cloudflare
            .post(requestBody)
            .addHeader("User-Agent", "PeregrinoGPS-Cloudflare/1.0")
            .addHeader("Accept", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Register network error", e)
                if (isAdded && !isDetached) {
                    requireActivity().runOnUiThread {
                        setLoadingState(false)
                        showError("Error de conexión. Verifica tu conexión a internet")
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                // ✅ MISMO MANEJO DE RESPUESTA
                handleAuthResponse(response, "register", email, password)
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
            .url("$BASE_URL/api/auth/login") // ✅ HTTPS Cloudflare
            .post(requestBody)
            .addHeader("User-Agent", "PeregrinoGPS-Cloudflare/1.0")
            .addHeader("Accept", "application/json")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Login network error", e)
                if (isAdded && !isDetached) {
                    requireActivity().runOnUiThread {
                        setLoadingState(false)
                        showError("Error de conexión. Verifica tu conexión a internet")
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                // ✅ MISMO MANEJO DE RESPUESTA
                handleAuthResponse(response, "login", email, password)
            }
        })
    }

    private fun handleAuthResponse(response: Response, action: String, email: String, password: String) {
        Log.d(TAG, "$action response: ${response.code}")

        var responseBody: String? = null
        var success = false
        var token = ""
        var errorMessage = ""

        try {
            responseBody = response.body?.string()
            Log.d(TAG, "$action response body (Cloudflare): $responseBody")

            if (response.isSuccessful && responseBody != null) {
                try {
                    val jsonResponse = JSONObject(responseBody)
                    token = jsonResponse.optString("token", "")

                    if (token.isNotEmpty()) {
                        success = true
                        Log.d(TAG, "$action successful via Cloudflare - token received")
                    } else {
                        errorMessage = "Token vacío recibido del servidor"
                        Log.e(TAG, "$action failed - empty token")
                    }
                } catch (jsonError: Exception) {
                    errorMessage = "Error procesando respuesta JSON"
                    Log.e(TAG, "$action JSON parse error", jsonError)
                }
            } else {
                errorMessage = when (response.code) {
                    400 -> if (action == "register") "Este email ya está registrado." else "Datos inválidos"
                    401 -> "Contraseña incorrecta. Verifica tus credenciales."
                    404 -> "Usuario no encontrado. ¿Necesitas crear una cuenta?"
                    422 -> "Datos inválidos. Verifica el formato del email."
                    500 -> "Error interno del servidor. Intenta más tarde."
                    else -> "Error ${response.code}. Intenta nuevamente."
                }
                Log.e(TAG, "$action failed with code ${response.code}")
            }

        } catch (e: Exception) {
            errorMessage = "Error leyendo respuesta del servidor"
            Log.e(TAG, "$action response handling error", e)
        } finally {
            response.close()
        }

        if (isAdded && !isDetached) {
            requireActivity().runOnUiThread {
                setLoadingState(false)

                if (success) {
                    try {
                        saveUserSession(token, email, password)

                        val successMsg = if (action == "register")
                            "¡Registro exitoso! Bienvenido a Peregrino GPS"
                        else "¡Bienvenido de vuelta!"

                        showSuccess(successMsg)
                        navigateToSecondFragment(token, email)

                    } catch (e: Exception) {
                        Log.e(TAG, "Error saving session or navigating", e)
                        showError("Error guardando sesión. Intenta nuevamente.")
                    }
                } else {
                    showError(errorMessage)
                }
            }
        }
    }

    // ✅ FUNCIÓN PARA MANEJAR ERRORES DE RED
    private fun handleNetworkError(action: String) {
        if (isAdded && !isDetached) {
            requireActivity().runOnUiThread {
                setLoadingState(false)
                showError("Error de conexión durante $action. Verifica tu conexión a internet.")
            }
        }
    }
    // ✅ FUNCIÓN SEPARADA PARA VERIFICAR SESIÓN GUARDADA
    private fun checkSavedSession() {
        try {
            val savedToken = sharedPreferences.getString("jwt_token", null)
            val savedEmail = sharedPreferences.getString("user_email", null)
            val savedPassword = sharedPreferences.getString("user_password", null)

            Log.d(TAG, "Checking saved session - Token: ${savedToken != null}, Email: ${savedEmail != null}")

            if (savedToken != null && savedEmail != null && savedPassword != null) {
                Log.d(TAG, "Valid session found, navigating to SecondFragment")

                // ✅ VERIFICAR QUE NO ESTAMOS YA NAVEGANDO
                if (isAdded && !isDetached && view != null) {
                    navigateToSecondFragment(savedToken, savedEmail)
                }
            } else {
                Log.d(TAG, "No valid session found, staying in FirstFragment")

                // ✅ LIMPIAR CAMPOS SI NO HAY SESIÓN VÁLIDA
                if (isAdded && view != null) {
                    clearInputFields()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking saved session", e)
            clearSession()
        }
    }

    // ✅ FUNCIÓN PARA LIMPIAR CAMPOS DE ENTRADA
    private fun clearInputFields() {
        try {
            if (_binding != null) {
                binding.editTextEmail.text?.clear()
                binding.editTextPassword.text?.clear()
                binding.emailInputLayout.error = null
                binding.passwordInputLayout.error = null
                setLoadingState(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing input fields", e)
        }
    }

    private fun setupClickListeners() {
        binding.buttonLogin.setOnClickListener {
            val email = binding.editTextEmail.text.toString().trim()
            val password = binding.editTextPassword.text.toString().trim()

            if (validateInput(email, password)) {
                setLoadingState(true, "Iniciando sesión...")
                login(email, password)
            }
        }

        binding.buttonRegister.setOnClickListener {
            val email = binding.editTextEmail.text.toString().trim()
            val password = binding.editTextPassword.text.toString().trim()

            if (validateInput(email, password)) {
                setLoadingState(true, "Creando cuenta...")
                register(email, password)
            }
        }
    }

    private fun validateInput(email: String, password: String): Boolean {
        when {
            email.isEmpty() -> {
                binding.emailInputLayout.error = "El email es requerido"
                return false
            }
            !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                binding.emailInputLayout.error = "Formato de email inválido"
                return false
            }
            password.isEmpty() -> {
                binding.passwordInputLayout.error = "La contraseña es requerida"
                return false
            }
            password.length < 6 -> {
                binding.passwordInputLayout.error = "La contraseña debe tener al menos 6 caracteres"
                return false
            }
            else -> {
                binding.emailInputLayout.error = null
                binding.passwordInputLayout.error = null
                return true
            }
        }
    }

    private fun setLoadingState(isLoading: Boolean, message: String = "") {
        if (_binding == null) return

        binding.buttonLogin.isEnabled = !isLoading
        binding.buttonRegister.isEnabled = !isLoading
        binding.editTextEmail.isEnabled = !isLoading
        binding.editTextPassword.isEnabled = !isLoading

        if (isLoading) {
            binding.buttonLogin.text = message
        } else {
            binding.buttonLogin.text = "Iniciar Sesión"
        }
    }


    // ✅ FUNCIÓN SEPARADA PARA GUARDAR SESIÓN
    private fun saveUserSession(token: String, email: String, password: String) {
        try {
            with(sharedPreferences.edit()) {
                putString("jwt_token", token)
                putString("user_email", email)
                putString("user_password", password)
                putLong("session_timestamp", System.currentTimeMillis())
                apply()
            }
            Log.d(TAG, "User session saved successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving user session", e)
        }
    }

    // ✅ FUNCIÓN PARA LIMPIAR SESIÓN
    fun clearSession() {
        try {
            with(sharedPreferences.edit()) {
                remove("jwt_token")
                remove("user_email")
                remove("user_password")
                remove("session_timestamp")
                apply()
            }
            Log.d(TAG, "Session cleared successfully")

            // ✅ LIMPIAR CAMPOS DE ENTRADA
            if (isAdded && view != null) {
                clearInputFields()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing session", e)
        }
    }

    private fun showError(message: String) {
        if (_binding == null || !isAdded) return

        try {
            if (message.contains("email", ignoreCase = true) || message.contains("formato", ignoreCase = true)) {
                binding.emailInputLayout.error = message
            } else if (message.contains("contraseña", ignoreCase = true)) {
                binding.passwordInputLayout.error = message
            } else {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing error message", e)
        }
    }

    private fun showSuccess(message: String) {
        if (!isAdded) return

        try {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing success message", e)
        }
    }

    private fun navigateToSecondFragment(token: String, email: String) {
        try {
            // ✅ VERIFICACIONES MÚLTIPLES ANTES DE NAVEGAR
            if (!isAdded || isDetached || _binding == null) {
                Log.w(TAG, "Cannot navigate - fragment not in valid state")
                return
            }

            val navController = findNavController()

            // ✅ VERIFICAR QUE NO ESTAMOS YA EN EL DESTINO
            if (navController.currentDestination?.id == R.id.SecondFragment) {
                Log.d(TAG, "Already in SecondFragment, skipping navigation")
                return
            }

            // ✅ VERIFICAR QUE EL DESTINO EXISTE
            if (navController.currentDestination?.id == R.id.FirstFragment) {
                Log.d(TAG, "Navigating to SecondFragment with token and email")

                val action = FirstFragmentDirections.actionFirstFragmentToSecondFragment(
                    jwtToken = token,
                    userEmail = email
                )

                navController.navigate(action)
            } else {
                Log.w(TAG, "Current destination is not FirstFragment: ${navController.currentDestination?.id}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to SecondFragment", e)

            // ✅ FALLBACK: MOSTRAR ERROR AL USUARIO
            showError("Error al navegar. Intenta iniciar sesión nuevamente.")
            clearSession()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "FirstFragment view destroyed")
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "FirstFragment destroyed")
    }
}