package com.zebass.peregrino

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import com.zebass.peregrino.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Configuración básica del NavController sin ActionBar
        val navController = findNavController(R.id.nav_host_fragment_content_main)
    }

    // Elimina los demás métodos relacionados con ActionBar/Menu
}