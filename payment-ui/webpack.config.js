const path = require('path');
const HtmlWebpackPlugin = require('html-webpack-plugin');

module.exports = {
  entry: './src/index.jsx',
  output: {
    path: path.resolve(__dirname, 'dist'),
    filename: '[name].[contenthash].js',
    publicPath: '/',
    clean: true,
  },
  resolve: {
    extensions: ['.js', '.jsx'],
  },
  module: {
    rules: [
      {
        test: /\.jsx?$/,
        exclude: /node_modules/,
        use: 'babel-loader', // presets live in babel.config.json
      },
      {
        // Emit the stylesheet as a file and import its URL, so no inline styles are needed (CSP).
        test: /\.css$/,
        type: 'asset/resource',
        generator: { filename: 'styles.[contenthash][ext]' },
      },
    ],
  },
  plugins: [
    // Generates dist/index.html and injects the bundle script tag.
    new HtmlWebpackPlugin({ template: './src/index.html' }),
  ],
  devServer: {
    port: 3000,
    static: {
      directory: path.resolve(__dirname, 'public'),
    },
    // Single-page app: unknown paths serve index.html.
    historyApiFallback: true,
  },
};
